package com.prituladima.plugin.intellij;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Host, port, user, key, password and price per hour of the remote computer; the price is required. The
 * fields are remembered for next time, except the password; the key and the price for each host. The key is filled in by itself, following the host as it is
 * typed: the one this host logged in with last time, else its IdentityFile in ~/.ssh/config, else the
 * standard ~/.ssh/id_ed25519, id_ecdsa or id_rsa; once chosen by hand, it stays. "Connect" logs in right
 * away; a failure is shown in the dialog.
 */
final class RemoteLoginDialog extends DialogWrapper {
  private static final String PREFIX = "com.prituladima.cobratorch.ssh.";

  private final Project project;
  private final JBTextField host = new JBTextField(28);
  private final JBTextField port = new JBTextField("22", 6);
  private final JBTextField user = new JBTextField(16);
  private final TextFieldWithBrowseButton key = new TextFieldWithBrowseButton();
  private final JBPasswordField password = new JBPasswordField();
  private final JBTextField price = new JBTextField(8);
  private boolean keyChosen;   // typed or picked by hand: it no longer follows the host
  private boolean priceTyped;
  private boolean filling;

  RemoteLoginDialog(Project project) {
    super(project, true);
    this.project = project;
    setTitle("Connect to a Remote Computer");
    setOKButtonText("Connect");
    PropertiesComponent saved = PropertiesComponent.getInstance();
    host.setText(saved.getValue(PREFIX + "host", ""));
    port.setText(saved.getValue(PREFIX + "port", "22"));
    user.setText(saved.getValue(PREFIX + "user", System.getProperty("user.name", "")));
    host.getEmptyText().setText("host, user@host, or a pasted ssh command");
    price.getEmptyText().setText("e.g. 2.50; 0 if it is free");
    fillKey();
    fillPrice();
    key.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        if (!filling) keyChosen = true;
      }
    });
    price.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        if (!filling) priceTyped = true;
      }
    });
    host.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        if (!keyChosen) fillKey();
        if (!priceTyped) fillPrice();
      }
    });
    key.addActionListener(e -> chooseKey());
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    return FormBuilder.createFormBuilder()
        .addLabeledComponent("Host:", host)
        .addLabeledComponent("Port:", port)
        .addLabeledComponent("User:", user)
        .addLabeledComponent("Private key:", key)
        .addComponentToRightColumn(small("Found by itself in ~/.ssh; the SSH agent's keys are tried after it."))
        .addLabeledComponent("Password:", password)
        .addComponentToRightColumn(small("The login password, or the passphrase of the key; not saved."))
        .addLabeledComponent("Price per hour, $:", price)
        .addComponentToRightColumn(small("What the computer costs while connected; shown with the cost so far."))
        .getPanel();
  }

  /** The price this host was connected at last time; empty for a new host, to be entered. */
  private void fillPrice() {
    String host = SshConnection.Login.parse(this.host.getText(), 22, "", null, new char[0]).host();
    String last = PropertiesComponent.getInstance().getValue(PREFIX + "price." + host.toLowerCase(Locale.ROOT), "");
    filling = true;
    try {
      price.setText(last);
    } finally {
      filling = false;
    }
  }

  /** {@code 2.5}, {@code $2.50}, {@code 2,50}, {@code 2.50/h} -> 2.5; null if it is not a price. */
  static @Nullable Double parsePrice(String text) {
    String t = text.trim().replace("$", "").replace(',', '.').replaceFirst("(?i)\\s*(/\\s*h|per\\s+hour|/hr)$", "").trim();
    if (t.isEmpty()) return null;
    try {
      double v = Double.parseDouble(t);
      return v >= 0 && Double.isFinite(v) ? v : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** The key this host logged in with last time, else the one {@link SshConnection#defaultKey} finds. */
  private void fillKey() {
    SshConnection.Login l = SshConnection.Login.parse(host.getText(), 22, "", null, new char[0]);
    String k = l.keyFile();   // -i of a pasted ssh command
    if (k == null) {
      String last = PropertiesComponent.getInstance().getValue(PREFIX + "key." + l.host().toLowerCase(Locale.ROOT));
      k = last != null && (last.isEmpty() || existing(last) != null) ? last : SshConnection.defaultKey(l.host());
    }
    filling = true;
    try {
      key.setText(k == null ? "" : k);
    } finally {
      filling = false;
    }
  }

  /** The file chooser, opened in ~/.ssh (or at the key shown), hidden files included. */
  private void chooseKey() {
    Path start = existing(key.getText().trim());
    if (start == null) start = existing(Path.of(System.getProperty("user.home"), ".ssh").toString());
    if (start == null) start = Path.of(System.getProperty("user.home"));
    VirtualFile toSelect = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(start);
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.singleFile().withTitle("Private Key").withShowHiddenFiles(true);
    FileChooser.chooseFile(descriptor, project, key, toSelect, file -> key.setText(file.toNioPath().toString()));
  }

  /** The file or folder at a typed path, ~ allowed; null if there is none or the path is not one. */
  private static @Nullable Path existing(String path) {
    if (path.isEmpty()) return null;
    try {
      Path p = Path.of(SshConnection.expandHome(path));
      return Files.exists(p) ? p : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }

  private static JBLabel small(String text) {
    JBLabel label = new JBLabel(text);
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setFont(JBUI.Fonts.smallFont());
    return label;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return host.getText().isBlank() ? host : password;
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    if (host.getText().isBlank()) return new ValidationInfo("Enter the host", host);
    if (price.getText().isBlank()) return new ValidationInfo("Enter the price per hour, 0 if it is free", price);
    if (parsePrice(price.getText()) == null) return new ValidationInfo("Enter the price as a number, like 2.50", price);
    return null;
  }

  @Override
  protected void doOKAction() {
    int p;
    try {
      p = Integer.parseInt(port.getText().trim());
    } catch (NumberFormatException e) {
      p = 22;
    }
    SshConnection.Login login = SshConnection.Login.parse(host.getText(), p, user.getText(), key.getText(), password.getPassword());
    if (login.user().isBlank()) {
      setErrorText("Enter the user", user);
      return;
    }
    Double perHour = parsePrice(price.getText());
    if (perHour == null) {
      setErrorText("Enter the price per hour, 0 if it is free", price);
      return;
    }
    try {
      ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
        RemoteHost.getInstance(project).connect(login, perHour);
        return null;
      }, "Connecting to " + login.label(), true, project);
    } catch (Exception e) {
      setErrorText(message(e));
      return;
    }
    PropertiesComponent saved = PropertiesComponent.getInstance();
    saved.setValue(PREFIX + "host", login.host());
    saved.setValue(PREFIX + "port", String.valueOf(login.port()));
    saved.setValue(PREFIX + "user", login.user());
    saved.setValue(PREFIX + "key." + login.host().toLowerCase(Locale.ROOT), login.keyFile() == null ? "" : login.keyFile());
    saved.setValue(PREFIX + "price." + login.host().toLowerCase(Locale.ROOT), price.getText().trim());
    super.doOKAction();
  }

  private static @NotNull String message(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
    String m = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : String.valueOf(root.getMessage());
    if (root instanceof java.net.UnknownHostException) return "Unknown host: " + root.getMessage();
    if (root instanceof java.net.ConnectException || root instanceof java.net.SocketTimeoutException) return "No answer: " + m;
    return m;
  }
}
