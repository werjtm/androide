/*
 * This file is part of the cSploit.
 *
 * Copyleft of Massimo Dragano aka tux_mind <tux_mind@csploit.org>
 *
 * cSploit is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * cSploit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with cSploit.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.evilsocket.dsploit.tools;

import it.evilsocket.dsploit.core.ExecChecker;
import it.evilsocket.dsploit.core.System;

/**
 * a shell with proper environment variables to run msf binaries
 */
public class MsfShell extends RubyShell {

  @Override
  public void setEnabled() {
    super.setEnabled();

    mEnabled = mEnabled && (ExecChecker.msf().getRoot() != null ||
            ExecChecker.msf().canExecuteInDir(System.getMsfPath()));
  }

  @Override
  protected void registerSettingReceiver() {
    super.registerSettingReceiver();
    onSettingsChanged.addFilter("MSF_DIR");
  }

  @Override
  protected void setupEnvironment() {
    super.setupEnvironment();

    mPreCmd += "export PATH=\"$PATH:" + ExecChecker.msf().getRoot() + "\"\n";
  }
}
