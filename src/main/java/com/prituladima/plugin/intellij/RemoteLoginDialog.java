package com.prituladima.plugin.intellij;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/**
 * Host, port, user, key and password of the remote computer. The fields are remembered for next time,
 * except the password. "Connect" logs in right away; a failure is shown in the dialog.
 */
final class RemoteLoginDialog extends DialogWrapper {
  private static final String PREFIX = "com.prituladima.cobratorch.ssh.";

  private final Project project;
  private final JBTextField host = new JBTextField(28);
  private final JBTextField port = new JBTextField("22", 6);
  private final JBTextField user = new JBTextField(16);
  private final TextFieldWithBrowseButton key = new TextFieldWithBrowseButton();
  private final JBPasswordField password = new JBPasswordField();

  RemoteLoginDialog(Project project) {
    super(project, true);
    this.project = project;
    setTitle("Connect to a Remote Computer");
    setOKButtonText("Connect");
    PropertiesComponent saved = PropertiesComponent.getInstance();
    host.setText(saved.getValue(PREFIX + "host", ""));
    port.setText(saved.getValue(PREFIX + "port", "22"));
    user.setText(saved.getValue(PREFIX + "user", System.getProperty("user.name", "")));
    key.setText(saved.getValue(PREFIX + "key", ""));
    host.getEmptyText().setText("host, user@host, or a pasted ssh command");
    key.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Private Key").withShowHiddenFiles(true));
    init();
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBLabel hint = new JBLabel("Without a key file: the SSH agent, then ~/.ssh/id_ed25519, id_ecdsa, id_rsa.");
    hint.setForeground(UIUtil.getContextHelpForeground());
    hint.setFont(JBUI.Fonts.smallFont());
    return FormBuilder.createFormBuilder()
        .addLabeledComponent("Host:", host)
        .addLabeledComponent("Port:", port)
        .addLabeledComponent("User:", user)
        .addLabeledComponent("Private key:", key)
        .addComponentToRightColumn(hint)
        .addLabeledComponent("Password:", password)
        .addComponentToRightColumn(small("The login password, or the passphrase of the key; not saved."))
        .getPanel();
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
    try {
      ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
        RemoteHost.getInstance(project).connect(login);
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
    saved.setValue(PREFIX + "key", login.keyFile() == null ? "" : login.keyFile());
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
