/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.bacon.cli;

import org.jboss.pnc.bacon.App;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Class to provide default behaviour when the sub-command is called without any options
 *
 */
public class SubCommandHelper implements Runnable {

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    /**
     * Print the usage message when  the sub-command is called with no options
     *
     * @return null
     */
    @Override
    public void run() {
        spec.commandLine().usage(System.err);
    }

}
