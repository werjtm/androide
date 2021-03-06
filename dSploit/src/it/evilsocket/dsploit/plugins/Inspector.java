/*
 * This file is part of the dSploit.
 *
 * Copyleft of Simone Margaritelli aka evilsocket <evilsocket@gmail.com>
 *
 * dSploit is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * dSploit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with dSploit.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.evilsocket.dsploit.plugins;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;

import it.evilsocket.dsploit.R;
import it.evilsocket.dsploit.core.ChildManager;
import it.evilsocket.dsploit.core.Logger;
import it.evilsocket.dsploit.core.Plugin;
import it.evilsocket.dsploit.core.System;
import it.evilsocket.dsploit.net.Network;
import it.evilsocket.dsploit.net.Target;
import it.evilsocket.dsploit.net.Target.Port;
import it.evilsocket.dsploit.tools.NMap.InspectionReceiver;

public class Inspector extends Plugin{

  private ToggleButton mStartButton = null;
  private ProgressBar mActivity = null;
  private TextView mDeviceType = null;
  private TextView mDeviceOS = null;
  private TextView mDeviceServices = null;
  private boolean mRunning = false;
  private boolean mFocusedScan = false;
  private Receiver mReceiver = null;
  private String empty = null;

  public Inspector(){
    super(
      R.string.inspector,
      R.string.inspector_desc,

      new Target.Type[]{Target.Type.ENDPOINT, Target.Type.REMOTE},
      R.layout.plugin_inspector,
      R.drawable.action_inspect
    );
  }

  private void setStoppedState(){
    if(mProcess!=null) {
      mProcess.kill();
      mProcess = null;
    }

    mActivity.setVisibility(View.INVISIBLE);
    mRunning = false;
    mStartButton.setChecked(false);
  }

  private void write_services()
  {
    synchronized (mDeviceServices) {
      StringBuilder sb = new StringBuilder();
      for (Port port : System.getCurrentTarget().getOpenPorts()) {
        if (port.service != null && !port.service.isEmpty()) {
          sb.append(port.number);
          sb.append(" ( ");
          sb.append(port.protocol.toString());
          sb.append(" ) : ");
          sb.append(port.service);

          if (port.version != null && !port.version.isEmpty()) {
            sb.append(" - ");
            sb.append(port.version);
          }
          sb.append("\n");
        }
      }
      if (sb.length() > 0)
        mDeviceServices.setText(sb.toString());
      else
        mDeviceServices.setText(empty);
    }
  }

  private void setStartedState(){

    try {
      Target target = System.getCurrentTarget();

      write_services();

      mProcess = System.getTools().nmap.inpsect( target, mReceiver, mFocusedScan);

      mActivity.setVisibility(View.VISIBLE);
      mRunning = true;
    } catch (ChildManager.ChildNotStartedException e) {
      System.errorLogging(e);
      Toast.makeText(Inspector.this, "cannot start process", Toast.LENGTH_LONG).show();
    }
  }

  @Override
  public void onCreate(Bundle savedInstanceState){
	  SharedPreferences themePrefs = getSharedPreferences("THEME", 0);
		Boolean isDark = themePrefs.getBoolean("isDark", false);
		if (isDark)
			setTheme(R.style.Sherlock___Theme);
		else
			setTheme(R.style.AppTheme);
    super.onCreate(savedInstanceState);

    mStartButton = (ToggleButton) findViewById(R.id.inspectToggleButton);
    mActivity = (ProgressBar) findViewById(R.id.inspectActivity);
    TextView mDeviceName = (TextView) findViewById(R.id.deviceName);
    mDeviceType = (TextView) findViewById(R.id.deviceType);
    mDeviceOS = (TextView) findViewById(R.id.deviceOS);
    mDeviceServices = (TextView) findViewById(R.id.deviceServices);

    mFocusedScan = System.getCurrentTarget().hasOpenPorts();

    mDeviceName.setText(System.getCurrentTarget().toString());

    if(System.getCurrentTarget().getDeviceType() != null)
      mDeviceType.setText(System.getCurrentTarget().getDeviceType());

    if(System.getCurrentTarget().getDeviceOS() != null)
      mDeviceOS.setText(System.getCurrentTarget().getDeviceOS());

    empty = getText(R.string.unknown).toString();

    write_services();

    mStartButton.setOnClickListener(new OnClickListener(){
      @Override
      public void onClick(View v){
        if(mRunning){
          setStoppedState();
        } else{
          setStartedState();
        }
      }
    }
    );

    mReceiver = new Receiver();
  }

  @Override
  public void onBackPressed(){
    setStoppedState();
    super.onBackPressed();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu){
    MenuInflater inflater = getSupportMenuInflater();
    inflater.inflate(R.menu.inspector, menu);
    return super.onCreateOptionsMenu(menu);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuItem item = menu.findItem(R.id.focused_scan);
    if(item != null) {
      item.setChecked(mFocusedScan);
      item.setEnabled(System.getCurrentTarget().hasOpenPorts());
    }
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item){
    switch (item.getItemId()) {
      case R.id.focused_scan:
        if(item.isChecked()) {
          item.setChecked(false);
          mFocusedScan =false;
        } else {
          item.setChecked(true);
          mFocusedScan =true;
        }
        return true;
      default:
        return super.onOptionsItemSelected(item);
    }
  }

  private class Receiver extends InspectionReceiver{
    @Override
    public void onServiceFound( final int port, final String protocol, final String service, final String version ){
      boolean hasServiceDescription = !service.trim().isEmpty();
      final boolean hasVersion = (version != null && !version.isEmpty());

      if(hasServiceDescription){
        if(hasVersion) {
          System.addOpenPort( port, Network.Protocol.fromString(protocol), service, version );
        }
        else
          System.addOpenPort( port, Network.Protocol.fromString(protocol), service );
      }
      else
        System.addOpenPort(port, Network.Protocol.fromString(protocol));

      Inspector.this.runOnUiThread(new Runnable(){

        @Override
        public void run(){
          write_services();
        }
      });
    }

    @Override
    public void onOpenPortFound(int port, String protocol){
      System.addOpenPort(port, Network.Protocol.fromString(protocol));
    }

    @Override
    public void onOsFound(final String os){
      System.getCurrentTarget().setDeviceOS(os);

      Inspector.this.runOnUiThread(new Runnable(){
        @Override
        public void run(){
          mDeviceOS.setText(os);
        }
      });
    }

    @Override
    public void onDeviceFound(final String device){
      System.getCurrentTarget().setDeviceType(device);

      Inspector.this.runOnUiThread(new Runnable(){
        @Override
        public void run(){
          mDeviceType.setText(device);
        }
      });
    }

    @Override
    public void onEnd(int code){
      Inspector.this.runOnUiThread(new Runnable(){
        @Override
        public void run(){
          if(mRunning)
            setStoppedState();
        }
      });
		}
	}
}
