package com.prituladima.plugin.intellij;

import com.jcraft.jsch.AgentIdentityRepository;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SSHAgentConnector;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UnixDomainSocketFactory;
import com.jcraft.jsch.UserInfo;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One SSH connection (JSch, pure Java: the same on Linux, Windows and macOS). Login with a password, a key
 * file, the SSH agent (Linux and macOS) or the usual ~/.ssh/id_* keys. Host keys are trusted on first use
 * and kept in the plugin's own known_hosts file; a host whose key later changes is refused.
 * Every remote command runs under {@code sh -c}, whatever the login shell is.
 */
final class SshConnection implements AutoCloseable {
  private static final int CONNECT_TIMEOUT_MS = 15_000;

  /** {@code secret}: the password, or the passphrase of the key; may be empty. */
  record Login(String host, int port, String user, @Nullable String keyFile, char[] secret) {
    String label() {
      return user + "@" + host + (port == 22 ? "" : ":" + port);
    }

    /**
     * Also takes a pasted ssh command, the way rented-GPU sites give it:
     * {@code ssh -p 41234 -i ~/.ssh/key root@ssh5.vast.ai -L 8080:localhost:8080}; its user, host, port and
     * key win over the separate fields.
     */
    static Login parse(String hostField, int port, String user, @Nullable String keyFile, char[] secret) {
      String host = hostField.trim();
      if (host.startsWith("ssh://")) host = host.substring("ssh://".length());
      String[] words = host.split("\\s+");
      if (words.length > 1 || host.contains("@")) {
        host = "";
        for (int i = words[0].equals("ssh") ? 1 : 0; i < words.length; i++) {
          String w = words[i];
          if ((w.equals("-p") || w.equals("-l") || w.equals("-i")) && i + 1 < words.length) {
            String value = words[++i];
            switch (w) {
              case "-p" -> port = parsePort(value, port);
              case "-l" -> user = value;
              default -> keyFile = expandHome(value);
            }
          } else if (w.startsWith("-")) {
            if (w.length() == 2 && "LRDbcEeFIiJlmOopQSWw".indexOf(w.charAt(1)) >= 0 && i + 1 < words.length) i++;   // option with a value
          } else if (host.isEmpty()) {
            host = w;
          }
        }
        int at = host.lastIndexOf('@');
        if (at >= 0) {
          user = host.substring(0, at);
          host = host.substring(at + 1);
        }
      }
      int colon = host.lastIndexOf(':');
      if (colon > 0 && host.indexOf(':') == colon) {   // host:port, not an IPv6 address
        port = parsePort(host.substring(colon + 1), port);
        host = host.substring(0, colon);
      }
      return new Login(host, port, user.trim(), keyFile == null || keyFile.isBlank() ? null : expandHome(keyFile.trim()), secret);
    }

    private static int parsePort(String s, int fallback) {
      try {
        int p = Integer.parseInt(s.trim());
        return p > 0 && p < 65536 ? p : fallback;
      } catch (NumberFormatException e) {
        return fallback;
      }
    }

    private static String expandHome(String path) {
      return path.startsWith("~") ? System.getProperty("user.home") + path.substring(1) : path;
    }
  }

  record Result(int exitCode, String out, String err) {}

  private final Session session;
  private final Login login;

  private SshConnection(Session session, Login login) {
    this.session = session;
    this.login = login;
  }

  static SshConnection open(Login login, Path knownHosts) throws JSchException, IOException {
    JSch jsch = new JSch();
    Files.createDirectories(knownHosts.getParent());
    if (!Files.exists(knownHosts)) Files.createFile(knownHosts);
    jsch.setKnownHosts(knownHosts.toString());
    byte[] passphrase = login.secret().length == 0 ? null : new String(login.secret()).getBytes(StandardCharsets.UTF_8);
    if (login.keyFile() != null) {
      jsch.addIdentity(login.keyFile(), passphrase);
    } else if (!useAgent(jsch)) {
      Path ssh = Path.of(System.getProperty("user.home"), ".ssh");
      for (String name : List.of("id_ed25519", "id_ecdsa", "id_rsa")) {
        Path key = ssh.resolve(name);
        if (Files.isRegularFile(key)) {
          try {
            jsch.addIdentity(key.toString(), passphrase);
          } catch (JSchException e) {
            // an encrypted key without its passphrase, or a format JSch cannot read: try the others
          }
        }
      }
    }
    Session session = jsch.getSession(login.user(), login.host(), login.port());
    session.setUserInfo(new Prompts(login.secret()));
    if (login.secret().length > 0) session.setPassword(new String(login.secret()).getBytes(StandardCharsets.UTF_8));
    session.setConfig("StrictHostKeyChecking", "ask");
    session.setConfig("HashKnownHosts", "yes");
    session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
    session.setServerAliveInterval(30_000);
    session.setServerAliveCountMax(3);
    try {
      session.connect(CONNECT_TIMEOUT_MS);
    } catch (JSchException e) {
      String m = String.valueOf(e.getMessage());
      if (m.startsWith("HostKey has been changed") || m.startsWith("reject HostKey")) {
        throw new JSchException("The host key of " + login.host() + " is not the one seen before. If this is a new "
            + "machine at the same address, delete the line of " + login.host() + " in " + knownHosts + " and connect again.");
      }
      if (m.equals("Auth fail") || m.startsWith("Auth cancel") || m.contains("Too many authentication failures")) {
        throw new JSchException("Login refused for " + login.label() + ": wrong password or key");
      }
      throw e;
    }
    return new SshConnection(session, login);
  }

  /** The SSH agent on Linux and macOS, when it holds at least one key. */
  private static boolean useAgent(JSch jsch) {
    if (System.getenv("SSH_AUTH_SOCK") == null || System.getProperty("os.name", "").startsWith("Windows")) return false;
    try {
      AgentIdentityRepository agent = new AgentIdentityRepository(new SSHAgentConnector(new UnixDomainSocketFactory()));
      if (agent.getIdentities().isEmpty()) return false;
      jsch.setIdentityRepository(agent);
      return true;
    } catch (Exception | LinkageError e) {
      return false;
    }
  }

  Login login() {
    return login;
  }

  boolean isConnected() {
    return session.isConnected();
  }

  /** Runs a shell script and waits for it, at most {@code timeoutMs}; stdin is the given bytes, if any. */
  Result exec(String script, byte @Nullable [] stdin, long timeoutMs) throws JSchException, IOException {
    ChannelExec channel = (ChannelExec) session.openChannel("exec");
    channel.setCommand(sh(script));
    channel.setInputStream(stdin == null ? null : new ByteArrayInputStream(stdin));
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    channel.setErrStream(err, true);
    InputStream in = channel.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    channel.connect(CONNECT_TIMEOUT_MS);
    long deadline = System.currentTimeMillis() + timeoutMs;
    byte[] buf = new byte[8192];
    try {
      while (true) {
        while (in.available() > 0) {
          int n = in.read(buf);
          if (n < 0) break;
          out.write(buf, 0, n);
        }
        if (channel.isClosed() && in.available() == 0) break;
        if (System.currentTimeMillis() > deadline) throw new IOException("Timed out: " + script);
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted", e);
        }
      }
      return new Result(channel.getExitStatus(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      channel.disconnect();
    }
  }

  /**
   * A command with a terminal, for the console: the remote process stops when the channel closes, and the
   * terminal does not echo, since the console shows what is typed itself. TERM=dumb keeps progress bars
   * to plain lines. Not connected yet: the caller takes the streams first, then connects.
   */
  ChannelExec terminalChannel(String script) throws JSchException {
    ChannelExec channel = (ChannelExec) session.openChannel("exec");
    channel.setCommand(sh(script));
    channel.setPty(true);
    channel.setPtyType("dumb", 160, 40, 0, 0);
    channel.setTerminalMode(new byte[] {53, 0, 0, 0, 0, 0});   // ECHO off, then TTY_OP_END
    return channel;
  }

  /** localhost:port on this computer goes to localhost:port on the remote one. */
  void forward(int port) throws JSchException {
    session.setPortForwardingL("127.0.0.1", port, "127.0.0.1", port);
  }

  void unforward(int port) {
    try {
      session.delPortForwardingL("127.0.0.1", port);
    } catch (JSchException ignored) {
      // already gone
    }
  }

  @Override
  public void close() {
    session.disconnect();
  }

  /** {@code sh -c '<script>'}, so the script runs the same under bash, zsh or fish. */
  static String sh(String script) {
    return "sh -c " + quote(script);
  }

  /** A POSIX shell word: single quotes, with each ' written as '"'"'. */
  static String quote(String s) {
    return "'" + s.replace("'", "'\"'\"'") + "'";
  }

  /** Answers JSch's questions from what the login dialog asked: the secret, and trust on first use. */
  private record Prompts(char[] secret) implements UserInfo, UIKeyboardInteractive {
    @Override
    public String getPassphrase() {
      return new String(secret);
    }

    @Override
    public String getPassword() {
      return new String(secret);
    }

    @Override
    public boolean promptPassword(String message) {
      return secret.length > 0;
    }

    @Override
    public boolean promptPassphrase(String message) {
      return secret.length > 0;
    }

    /** Yes to an unknown host (it is remembered); no to a host whose key changed. */
    @Override
    public boolean promptYesNo(String message) {
      return message != null && message.startsWith("The authenticity of host");
    }

    @Override
    public void showMessage(String message) {
    }

    @Override
    public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
      if (secret.length == 0) return null;
      String[] answers = new String[prompt.length];
      Arrays.fill(answers, new String(secret));
      return answers;
    }
  }

  /** Lines of text, without empty ones. */
  static List<String> lines(String text) {
    List<String> lines = new ArrayList<>();
    for (String line : text.split("\\R")) if (!line.isBlank()) lines.add(line.strip());
    return lines;
  }
}
