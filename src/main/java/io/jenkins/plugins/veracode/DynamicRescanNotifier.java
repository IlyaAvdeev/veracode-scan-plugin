package io.jenkins.plugins.veracode;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.kohsuke.stapler.StaplerRequest;

import com.veracode.apiwrapper.cli.VeracodeCommand.VeracodeParser;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.ArgumentListBuilder;
import io.jenkins.plugins.veracode.VeracodeNotifier.VeracodeDescriptor;
import io.jenkins.plugins.veracode.args.DynamicRescanArgs;
import io.jenkins.plugins.veracode.common.Constant;
import io.jenkins.plugins.veracode.utils.EncryptionUtil;
import io.jenkins.plugins.veracode.utils.FileUtil;
import io.jenkins.plugins.veracode.utils.RemoteScanUtil;
import io.jenkins.plugins.veracode.utils.StringUtil;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/**
 * Contains the code that is executed after a job that is configured to use the
 * Veracode plugin is built and provides getter methods for the form fields
 * defined in config.jelly.
 * <p>
 *
 * This class extends the {@link hudson.tasks.Notifier Notifier} class.
 */
public class DynamicRescanNotifier extends Notifier {

    /**
     * Contains the code that is executed after a user submits the "Configure
     * System" form and provides getter methods for the form fields defined in
     * global.jelly.
     * <p>
     * This class extends the {@link hudson.tasks.BuildStepDescriptor
     * BuildStepDescriptor} class.
     *
     * Converting this class to a top-level class should be done with the
     * understanding that doing so might prevent the plugin from working properly if
     * not at all.
     *
     */
    @hudson.Extension
    public static final class DynamicScanDescriptor extends BuildStepDescriptor<Publisher> {

        private static final String PostBuildActionDisplayText = "Dynamic Rescan with Veracode";

        private boolean failbuild;
        private boolean autoappname;
        private boolean debug;
        private boolean autoversion;

        // -------------------------------------------------------------------
        // Methods that correspond to identifiers referenced in global.jelly
        // -------------------------------------------------------------------

        public boolean getFailbuild() {
            return failbuild;
        }

        public boolean getAutoappname() {
            return autoappname;
        }

        public boolean getDebug() {
            return debug;
        }

        public boolean getAutoversion() {
            return autoversion;
        }

        /**
         * The name of the plugin displayed in the UI.
         */
        @Override
        public String getDisplayName() {
            return getPostBuildActionDisplayText();
        }

        /**
         * Whether this task is applicable to the given project.
         */
        @SuppressWarnings("rawtypes")
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This constructor makes it possible for global configuration data to be
         * re-loaded after Jenkins is restarted.
         */
        public DynamicScanDescriptor() {
            super(DynamicRescanNotifier.class);
            load();
        }

        public static String getPostBuildActionDisplayText() {
            return PostBuildActionDisplayText;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            updateFromGlobalConfiguration();
            req.bindJSON(this, formData);
            save();
            return super.configure(req, formData);
        }

        private void updateFromGlobalConfiguration() {
            VeracodeDescriptor globalVeracodeDescriptor = (VeracodeDescriptor) Jenkins.getInstance()
                    .getDescriptor(VeracodeNotifier.class);
            if (globalVeracodeDescriptor != null) {
                failbuild = globalVeracodeDescriptor.getFailbuild();
                autoappname = globalVeracodeDescriptor.getAutoappname();
                debug = globalVeracodeDescriptor.getDebug();
                autoversion = globalVeracodeDescriptor.getAutoversion();
            }
        }
    }

    // --------------------------------------------------------------------------------------
    // Backing fields for methods that correspond to identifiers referenced in
    // config.jelly
    // --------------------------------------------------------------------------------------
    private final String _appname;
    private final boolean _dvrenabled;

    // -------------------------------------------------------------------
    // Methods that correspond to identifiers referenced in config.jelly
    // -------------------------------------------------------------------
    public String getAppname() {
        return EncryptionUtil.decrypt(this._appname);
    }

    public boolean getDvrenabled() {
        return this._dvrenabled;
    }

    @Override
    public DynamicScanDescriptor getDescriptor() {
        return (DynamicScanDescriptor) super.getDescriptor();
    }

    /**
     * Returns an object that represents the scope of the synchronization monitor
     * expected by the plugin.
     */
    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    /**
     * In this overridden method we are taking care of copying the wrapper to remote
     * location and making the build ready for scan
     **/

    @Override
    public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
        boolean bRet = false;
        getDescriptor().updateFromGlobalConfiguration();
        boolean debug = getDescriptor().getDebug();

        PrintStream ps = listener.getLogger();
        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            ps.print("\r\n\r\nFailed to locate the build workspace.\r\n");
            return !getDescriptor().getFailbuild();
        }
        boolean isRemoteWorkspace = workspace.isRemote();

        // only copy if remote workspace and the checkbox to copy from remote to
        // master is unchecked
        if (isRemoteWorkspace) {
            try {
                FilePath localWorkspaceFilePath = FileUtil.getLocalWorkspaceFilepath();
                FilePath remoteVeracodeFilePath = RemoteScanUtil.getRemoteVeracodePath(build);

                // create the directory (where we want to copy the javawrapper
                // jar) if it does not exist
                if (!remoteVeracodeFilePath.exists()) {

                    if (debug) {
                        ps.println("Making remote dir");
                    }

                    remoteVeracodeFilePath.mkdirs();
                }

                FilePath[] files = remoteVeracodeFilePath.list(Constant.inclusive);

                // copy the jar if it does not exist
                if (files.length == 0) {
                    bRet = FileUtil.copyJarFiles(build, localWorkspaceFilePath,
                            remoteVeracodeFilePath, ps);
                } else // if file exits
                {
                    FilePath[] newfiles = localWorkspaceFilePath.list(Constant.inclusive);
                    String newjarName = newfiles[0].getRemote();
                    int newVersion = RemoteScanUtil.getJarVersion(newjarName);
                    String oldjarName = files[0].getRemote();
                    int oldVersion = RemoteScanUtil.getJarVersion(oldjarName);

                    // also copy the jar if there is a newer version in the
                    // plugin directory and delete the old one
                    if (newVersion > oldVersion) {
                        if (debug) {
                            ps.println(
                                    "Newer veracode library version, copying it to remote machine");
                        }

                        remoteVeracodeFilePath.deleteContents();
                        bRet = FileUtil.copyJarFiles(build, localWorkspaceFilePath,
                                remoteVeracodeFilePath, ps);
                    } else // just make sure we have our jarfile (defensive
                           // coding)
                    {
                        String jarName = files[0].getRemote();
                        String newJarName = jarName.replaceAll(Constant.regex,
                                Constant.execJarFile + "$2");
                        Node node = build.getBuiltOn();
                        if (node == null) {
                            ps.print("\r\n\r\nFailed to locate the build node.\r\n");
                            return !getDescriptor().getFailbuild();
                        }
                        FilePath newjarFilePath = new FilePath(node.getChannel(), newJarName);

                        if (newjarFilePath.exists())
                            bRet = true;
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                if (getDescriptor().getFailbuild()) {
                    ps.print(ex.getMessage());
                }
            }
        } else
            bRet = true;

        return bRet;
    }

    /**
     * Called by Jenkins after a build for a job specified to use the plugin is
     * performed.
     */
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws IOException, InterruptedException {
        PrintStream ps = listener.getLogger();

        ps.println("------------------------------------------------------------------------");
        ps.println(DynamicScanDescriptor.getPostBuildActionDisplayText());
        ps.println("------------------------------------------------------------------------");

        getDescriptor().updateFromGlobalConfiguration();
        boolean debug = getDescriptor().getDebug();

        if (debug) {
            ps.println("\r\n[Debug mode is on]\r\n");

            ps.println(String.format("Can Fail Build?%n%s%n", getDescriptor().getFailbuild()));

            try {
                Method method = com.veracode.apiwrapper.cli.VeracodeCommand.class
                        .getDeclaredMethod("getVersionString");
                method.setAccessible(true);
                String version = (String) method.invoke(null);
                if (!StringUtil.isNullOrEmpty(version)) {
                    ps.println(String.format("Version information:%n%s", version));
                }
            } catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException e) {
                ps.println("Could not retrieve API wrapper's version information.");
            }
        }

        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            ps.print("\r\n\r\nFailed to locate the build workspace.\r\n");
            return !getDescriptor().getFailbuild();
        }
        boolean isRemoteWorkspace = workspace.isRemote();

        if (debug) {
            ps.println(String.format("%n%nProcessing files in [%s] workspace: ",
                    isRemoteWorkspace ? "remote" : "local"));
            String workspaceDir = workspace.getRemote();
            workspaceDir = workspaceDir.replace("\\", "/");
            listener.hyperlink("file://" + workspaceDir, workspaceDir);
        }

        File localWorkspaceDir = null;

        try {
            if (isRemoteWorkspace) {
                return runScanFromRemote(build, listener, ps, debug);
            }

            if (debug) {
                ps.print("\r\n\r\nBuilding arguments. ");
            }

            DynamicRescanArgs dynamicScanArguments = DynamicRescanArgs.dynamicScanArgs(this, build,
                    build.getEnvironment(listener));

            if (debug) {
                ps.println(String.format("Calling wrapper with arguments:%n%s%n",
                        Arrays.toString(dynamicScanArguments.getMaskedArguments())));
            }

            try {
                VeracodeParser parser = new VeracodeParser();
                parser.setOutputWriter(ps);
                parser.setErrorWriter(ps);
                parser.throwExceptions(true);
                int retcode = parser.parse(dynamicScanArguments.getArguments());
                if (retcode != 0) {
                    if (getDescriptor().getFailbuild()) {
                        ps.print("\r\n\r\nError- Returned code from wrapper:" + retcode + "\r\n\n");
                        return false;
                    }
                }
            } catch (Throwable e) {
                if (getDescriptor().getFailbuild()) {
                    ps.println();
                    return false;
                }
            }
            ps.println();
            return true;
        } finally {
            if (isRemoteWorkspace) {
                try {
                    if (localWorkspaceDir != null && localWorkspaceDir.exists()) {
                        FileUtil.deleteDirectory(localWorkspaceDir);
                    }
                } catch (Throwable e) {
                    ps.println(e.getMessage());
                }
            }
        }
    }

    /**
     * Called by Jenkins with data supplied in the "Job Configuration" page.
     *
     * @param appname    String
     * @param dvrenabled boolean
     */
    @org.kohsuke.stapler.DataBoundConstructor
    public DynamicRescanNotifier(String appname, boolean dvrenabled) {
        this._appname = appname;
        this._dvrenabled = dvrenabled;
    }

    // invoking the CLI from remote node
    private boolean runScanFromRemote(AbstractBuild<?, ?> build, BuildListener listener,
            PrintStream ps, boolean bDebug) {
        boolean bRet = false;

        Node node = build.getBuiltOn();

        FilePath remoteVeracodeFilePath = RemoteScanUtil.getRemoteVeracodePath(build);
        String jarFilePath = remoteVeracodeFilePath.getRemote();

        FilePath workspace = build.getWorkspace();
        if (workspace == null) {
            ps.print("\r\n\r\nFailed to locate the build workspace.\r\n");
            return !getDescriptor().getFailbuild();
        }
        String remoteworkspace = workspace.getRemote();
        String sep = RemoteScanUtil.getPathSeparator(remoteworkspace);

        // obtain the String file paths, using the includes/excludes patterns a
        // 2nd time
        try {
            DynamicRescanArgs dynamicScanArguments = DynamicRescanArgs.dynamicScanArgs(this, build,
                    build.getEnvironment(listener));

            String jarPath = jarFilePath + sep + Constant.execJarFile + ".jar";
            String cmd = "java -jar " + jarPath;
            String[] cmds = dynamicScanArguments.getArguments();

            StringBuilder result = new StringBuilder();
            result.append(cmd);
            for (String _cmd : cmds) {
                _cmd = RemoteScanUtil.formatParameterValue(_cmd);
                result.append(" " + _cmd);
            }

            ArgumentListBuilder command = new ArgumentListBuilder();
            command.addTokenized(result.toString());

            List<String> remoteCmd = command.toList();
            int iSize = remoteCmd.size();
            Integer[] iPos = RemoteScanUtil.getMaskPosition(remoteCmd);
            int iPosKey = iPos[0];
            int iPosProxyPassword = iPos[1];

            Launcher launcher = node.createLauncher(listener);
            ProcStarter procStart = launcher.new ProcStarter();

            // masking the password related information
            boolean[] masks = new boolean[iSize];
            for (int i = 0; i < iSize; i++) {
                if (iPosKey != -1) {
                    if (iPosKey == i)
                        masks[i] = true;
                } else if (iPosProxyPassword != -1) {
                    if (iPosProxyPassword == i)
                        masks[i] = true;
                } else
                    masks[i] = false;
            }

            procStart = procStart.cmds(command).masks(masks).stdout(listener).quiet(true);

            if (bDebug) {
                procStart.quiet(false);
                ps.print("\nInvoking the following command in remote workspace:\n");
            }
            Proc proc = launcher.launch(procStart);

            int retcode = proc.join();

            if (retcode != 0) {
                if (getDescriptor().getFailbuild()) {
                    ps.print("\r\n\r\nError- Returned code from wrapper:" + retcode + "\r\n\n");
                }

            } else if (retcode == 0) {
                bRet = true;
            }

        } catch (IOException | InterruptedException ex) {
            ex.printStackTrace();
            if (getDescriptor().getFailbuild()) {
                ps.print(ex.getMessage());
            }
        }
        return bRet;
    }
}