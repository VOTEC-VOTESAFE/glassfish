/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
 * Copyright (c) 2012, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.osgi.cli.interactive;

import com.sun.enterprise.admin.cli.ArgumentTokenizer;
import com.sun.enterprise.admin.cli.CLICommand;
import com.sun.enterprise.admin.cli.CLIUtil;
import com.sun.enterprise.admin.cli.Environment;
import com.sun.enterprise.admin.cli.MultimodeCommand;
import com.sun.enterprise.admin.cli.ProgramOptions;
import com.sun.enterprise.admin.cli.remote.RemoteCLICommand;
import com.sun.enterprise.admin.util.CommandModelData;
import com.sun.enterprise.universal.i18n.LocalStringsImpl;

import jakarta.inject.Inject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandModel.ParamModel;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.api.admin.InvalidCommandException;
import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.DynamicConfiguration;
import org.glassfish.hk2.api.DynamicConfigurationService;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jvnet.hk2.annotations.Service;

/**
 * A simple local asadmin sub-command to establish an interactive osgi shell.
 *
 * This class is forked from com.sun.enterprise.admin.cli.MultimodeCommand
 *
 * Original code authors:
 *      केदार(km@dev.java.net)
 *      Bill Shannon
 * @author ancoron
 */
@Service(name = "osgi-shell")
@I18n("osgi-shell")
@PerLookup
public class LocalOSGiShellCommand extends CLICommand {

    protected static final String REMOTE_COMMAND = "osgi";
    protected static final String SESSIONID_OPTION = "--session-id";
    protected static final String SESSION_OPTION = "--session";
    protected static final String SESSION_OPTION_EXECUTE = "execute";
    protected static final String SESSION_OPTION_START = "new";
    protected static final String SESSION_OPTION_STOP = "stop";
    private static final LocalStringsImpl STRINGS = new LocalStringsImpl(MultimodeCommand.class);

    @Inject
    private ServiceLocator locator;

    @Param(name = "instance", optional = true)
    private String instance;

    @Param(optional = true, shortName = "f")
    private File file;

    @Param(name = "printprompt", optional = true)
    private Boolean printPromptOpt;

    @Param(optional = true)
    private String encoding;

    private boolean echo;
    private RemoteCLICommand cmd;
    private String shellType;


    protected String[] enhanceForTarget(String[] args) {
        if (instance == null) {
            return null;
        }
        String[] targetArgs = new String[args.length + 2];
        targetArgs[1] = "--instance";
        targetArgs[2] = instance;
        System.arraycopy(args, 0, targetArgs, 0, 1);
        System.arraycopy(args, 1, targetArgs, 3, args.length - 1);
        return targetArgs;
    }

    protected String[] prepareArguments(String sessionId, String[] args) {
        if (sessionId == null) {
            String[] osgiArgs = new String[args.length + 1];
            osgiArgs[0] = REMOTE_COMMAND;
            System.arraycopy(args, 0, osgiArgs, 1, args.length);
            args = osgiArgs;
        } else {
            // attach command to remote session...
            String[] sessionArgs = new String[args.length + 5];
            sessionArgs[0] = REMOTE_COMMAND;
            sessionArgs[1] = SESSION_OPTION;
            sessionArgs[2] = SESSION_OPTION_EXECUTE;
            sessionArgs[3] = SESSIONID_OPTION;
            sessionArgs[4] = sessionId;
            System.arraycopy(args, 0, sessionArgs, 5, args.length);
            args = sessionArgs;
        }
        return args;
    }

    protected String startSession() throws CommandException {
        if (!"gogo".equals(shellType)) {
            return null;
        }
        String[] args = {REMOTE_COMMAND, SESSION_OPTION, SESSION_OPTION_START};
        return cmd.executeAndReturnOutput(enhanceForTarget(args)).trim();
    }

    protected int stopSession(String sessionId) throws CommandException {
        if (sessionId == null) {
            return 0;
        }
        String[] args = {REMOTE_COMMAND, SESSION_OPTION, SESSION_OPTION_STOP, SESSIONID_OPTION, sessionId};
        return cmd.execute(enhanceForTarget(args));
    }

    /**
     * The validate method validates that the type and quantity of
     * parameters and operands matches the requirements for this
     * command.  The validate method supplies missing options from
     * the environment.
     */
    @Override
    protected void validate() throws CommandException, CommandValidationException {
        // Save value of --echo because CLICommand will reset it
        // before calling our executeCommand method but we want it
        // to also apply to all commands in multimode.
        echo = programOpts.isEcho();
    }

    /**
     * In the usage message modify the --printprompt option to have a
     * default based on the --interactive option.
     */
    @Override
    protected Collection<ParamModel> usageOptions() {
        Collection<ParamModel> opts = commandModel.getParameters();
        Set<ParamModel> uopts = new LinkedHashSet<>();
        ParamModel p = new CommandModelData.ParamModelData("printprompt",
                boolean.class, true,
                Boolean.toString(programOpts.isInteractive()));
        for (ParamModel pm : opts) {
            if (pm.getName().equals("printprompt")) {
                uopts.add(p);
            } else {
                uopts.add(pm);
            }
        }
        return uopts;
    }

    @Override
    protected int executeCommand() throws CommandException, CommandValidationException {
        if (cmd == null) {
            throw new CommandException("Remote command 'osgi' is not available.");
        }
        programOpts.setEcho(echo);       // restore echo flag, saved in validate
            if (encoding != null) {
                // see Configuration.getEncoding()...
                System.setProperty("input.encoding", encoding);
            }
            final String[] args = enhanceForTarget(new String[] {REMOTE_COMMAND, "asadmin-osgi-shell"});
            shellType = cmd.executeAndReturnOutput(args).trim();
        try (Terminal terminal = createTerminal()) {
            LineReader reader = LineReaderBuilder.builder().completer(getCommandCompleter()).appName(REMOTE_COMMAND).terminal(terminal).build();
            return executeCommands(reader);
        } catch (IOException e) {
            throw new CommandException(e);
        }
    }


    private Terminal createTerminal() throws IOException, CommandException {
        if (file == null) {
            System.out.println(STRINGS.get("multimodeIntro"));
            return TerminalBuilder.builder().streams(new FileInputStream(FileDescriptor.in), System.out).build();
        }
        if (!file.canRead()) {
            throw new CommandException("File: " + file + " can not be read");
        }

        OutputStream out = new OutputStream() {

            @Override
            public void write(int b) throws IOException {
                return;
            }

            @Override
            public void write(byte[] b) throws IOException {
                return;
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                return;
            }
        };
        return TerminalBuilder.builder().streams(new FileInputStream(file), out).build();
    }


    /**
     * Get the command completion.
     *
     * @return The command completer
     */
    private Completer getCommandCompleter() {
        if("gogo".equals(shellType)) {
            return new StringsCompleter(
                    "bundlelevel",
                    "cd",
                    "frameworklevel",
                    "headers",
                    "help",
                    "inspect",
                    "install",
                    "lb",
                    "log",
                    "ls",
                    "refresh",
                    "resolve",
                    "start",
                    "stop",
                    "uninstall",
                    "update",
                    "which",
                    "cat",
                    "each",
                    "echo",
                    "format",
                    "getopt",
                    "gosh",
                    "grep",
                    "not",
                    "set",
                    "sh",
                    "source",
                    "tac",
                    "telnetd",
                    "type",
                    "until",
                    "deploy",
                    "info",
                    "javadoc",
                    "list",
                    "repos",
                    "source"
                    );
        } else if("felix".equals(shellType)) {
            return new StringsCompleter(
                    "exit",
                    "quit",
                    "help",
                    "bundlelevel",
                    "cd",
                    "find",
                    "headers",
                    "inspect",
                    "install",
                    "log",
                    "ps",
                    "refresh",
                    "resolve",
                    "scr",
                    "shutdown",
                    "start",
                    "startlevel",
                    "stop",
                    "sysprop",
                    "uninstall",
                    "update",
                    "version"
                    );
        }

        return new NullCompleter();
    }


    /**
     * Read commands from the specified BufferedReader
     * and execute them.  If printPrompt is set, prompt first.
     *
     * @return the exit code of the last command executed
     */
    private int executeCommands(LineReader reader) throws CommandException, CommandValidationException {
        String line = null;
        int rc = 0;

        /*
         * Any program options we start with are copied to the environment
         * to serve as defaults for commands we run, and then we give each
         * command an empty program options.
         */
        programOpts.toEnvironment(env);

        String sessionId = startSession();

        try {
            for (;;) {
                if (isPromptPrinted()) {
                    line = reader.readLine(shellType + "$ ");
                } else {
                    line = reader.readLine();
                }

                if (line == null) {
                    if (isPromptPrinted()) {
                        System.out.println();
                    }
                    break;
                }

                if (line.trim().startsWith("#")) // ignore comment lines
                {
                    continue;
                }

                String[] args = null;
                try {
                    args = getArgs(line);
                } catch (ArgumentTokenizer.ArgumentException ex) {
                    logger.info(ex.getMessage());
                    continue;
                }

                if (args.length == 0) {
                    continue;
                }

                String command = args[0];
                if (command.trim().length() == 0) {
                    continue;
                }

                // handle built-in exit and quit commands
                // XXX - care about their arguments?
                if (command.equals("exit") || command.equals("quit")) {
                    break;
                }

                ProgramOptions po = null;
                try {
                    /*
                     * Every command gets its own copy of program options
                     * so that any program options specified in its
                     * command line options don't effect other commands.
                     * But all commands share the same environment.
                     */
                    po = new ProgramOptions(env);
                    // copy over AsadminMain info
                    po.setClassPath(programOpts.getClassPath());
                    po.setClassName(programOpts.getClassName());
                    // remove the old one and replace it
                    atomicReplace(locator, po);

                    args = prepareArguments(sessionId, args);

                    args = enhanceForTarget(args);

                    String output = cmd.executeAndReturnOutput(args).trim();
                    if(output != null && output.length() > 0) {
                        logger.info(output);
                    }
                } catch (CommandValidationException cve) {
                    logger.severe(cve.getMessage());
                    logger.severe(cmd.getUsage());
                    rc = ERROR;
                } catch (InvalidCommandException ice) {
                    // find closest match with local or remote commands
                    logger.severe(ice.getMessage());
                } catch (CommandException ce) {
                    logger.severe(ce.getMessage());
                    rc = ERROR;
                } finally {
                    // restore the original program options
                    // XXX - is this necessary?
                    atomicReplace(locator, programOpts);
                }

                CLIUtil.writeCommandToDebugLog(name, env, args, rc);
            }
        } finally {
            // what if something breaks on the wire?
            rc = stopSession(sessionId);
        }
        return rc;
    }

    private boolean isPromptPrinted() {
        return file == null;
    }

    private static void atomicReplace(ServiceLocator locator, ProgramOptions options) {
        DynamicConfigurationService dcs = locator.getService(DynamicConfigurationService.class);
        DynamicConfiguration config = dcs.createDynamicConfiguration();

        config.addUnbindFilter(BuilderHelper.createContractFilter(ProgramOptions.class.getName()));
        ActiveDescriptor<ProgramOptions> desc = BuilderHelper.createConstantDescriptor(
                options, null, ProgramOptions.class);
        config.addActiveDescriptor(desc);

        config.commit();
    }


    private String[] getArgs(String line) throws ArgumentTokenizer.ArgumentException {
        List<String> args = new ArrayList<>();
        ArgumentTokenizer t = new ArgumentTokenizer(line);
        while (t.hasMoreTokens()) {
            args.add(t.nextToken());
        }
        return args.toArray(new String[args.size()]);
    }

    @Override
    public void postConstruct() {
        super.postConstruct();
        try {
            cmd = new RemoteCLICommand(REMOTE_COMMAND,
                locator.<ProgramOptions>getService(ProgramOptions.class),
                locator.<Environment>getService(Environment.class));
        } catch (CommandException ex) {
            // ignore - will be handled by execute()
        }
    }
}
