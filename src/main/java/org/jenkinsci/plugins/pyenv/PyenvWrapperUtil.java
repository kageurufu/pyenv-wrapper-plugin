package org.jenkinsci.plugins.pyenv;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.util.ArgumentListBuilder;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public class PyenvWrapperUtil {

  private FilePath workspace;
  private String name;
  private Launcher launcher;
  private TaskListener listener;

  private static final List<String> PYENV_PATHS = Arrays.asList(
    "~/tools/pyenv/bin",
    "~/.pyenv/bin"
  );

  PyenvWrapperUtil(final FilePath workspace, final String name, Launcher launcher, TaskListener listener) {
    this.workspace = workspace;
    this.name = name;
    this.listener = listener;
    this.launcher = launcher;
  }

  private String getBashScript(String version) {
      return PYENV_PATHS.stream().map(path -> "{ \n" +
          "  [ -d " + path + " ] && \n" +
          "  export PATH=" + path + ":$PATH && \n"+
          "  eval \"$(" + path + "/pyenv init - )\"; \n"+
          "}")
      .collect(Collectors.joining(" || "))
      + " && {\n"
      + "  pyenv versions --skip-aliases | grep -q \"" + version + "\" \\\n"
      + "  || pyenv install -s \"" + version + "\"; \n"
      + "} && {\n"
      + "  pyenv versions --skip-aliases | grep -q \"" + name + "-" + version + "\" \\\n"
      + "  || pyenv virtualenv " + version + " \"" + name + "-" + version + "\";\n"
      + "} \\\n"
      + "&& export PYENV_VERSION=\"" + name + "-" + version + "\" \\\n"
      + "&& export > pyenv.env"
      ;
  }

  public Map<String, String> getPyenvEnvVars(String version, String pyenvInstallURL)
    throws IOException, InterruptedException {

    int statusCode = installPyenv(Optional.ofNullable(pyenvInstallURL).orElse(PyenvDefaults.pyenvInstallURL));
    if (statusCode != 0) {
      throw new AbortException("Failed to install Pyenv");
    }

    ArgumentListBuilder beforeCmd = new ArgumentListBuilder();
    beforeCmd.add("bash");
    beforeCmd.add("-c");
    beforeCmd.add("export > before.env");

    Map<String, String> beforeEnv = toMap(getExport(beforeCmd, "before.env"));

    ArgumentListBuilder pyenvSourceCmd = new ArgumentListBuilder();
    pyenvSourceCmd.add("bash");
    pyenvSourceCmd.add("-c");
    pyenvSourceCmd.add(getBashScript(version));

    Map<String, String> afterEnv = toMap(getExport(pyenvSourceCmd, "pyenv.env"));

    Map<String, String> newEnvVars = new HashMap<>();

    afterEnv.forEach((k, v) -> {
      String beforeValue = beforeEnv.get(k);
      if (!v.equals(beforeValue)) {

        if (k.equals("PATH")) {
          String path = Arrays.stream(v.split(File.pathSeparator))
            .filter(it -> it.matches(".*\\.pyenv.*"))
            .collect(Collectors.joining(File.pathSeparator));
          newEnvVars.put("PATH", afterEnv.get("PATH"));
          newEnvVars.put("PATH+PYENV", path);
        } else {
          newEnvVars.put(k, v);
        }

      }
    });

    return newEnvVars;
  }

  private String getExport(ArgumentListBuilder args, String destFile) throws IOException, InterruptedException {

    Integer statusCode = launcher.launch().pwd(workspace).cmds(args)
      .stdout(listener.getLogger())
      .stderr(listener.getLogger()).join();

    if (statusCode != 0) {
      throw new AbortException("Failed to fork bash ");
    }

    return workspace.child(destFile).readToString();

  }

  private Integer installPyenv(String pyenvInstallURL) throws IOException, InterruptedException {
    listener.getLogger().println("Installing pyenv\n");
    FilePath installer = workspace.child("pyenv-installer");
    installer.copyFrom(new URL(pyenvInstallURL));
    installer.chmod(0755);
    ArgumentListBuilder args = new ArgumentListBuilder();

    args.add(installer.absolutize().getRemote());

    return launcher.launch().cmds(args).pwd(workspace)
      .stdout(listener.getLogger())
      .stderr(listener.getLogger()).join();
  }

  private Map<String, String> toMap(String export) {
    Map<String, String> r = new HashMap<>();

    Arrays.asList(export.split("[\n|\r]")).forEach(line -> {
      String[] entry = line.replaceAll("declare -x ", "").split("=");
      if (entry.length == 2) {
        r.put(entry[0], entry[1].replaceAll("\"", ""));
      }

    });
    return r;
  }
}
