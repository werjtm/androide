package it.evilsocket.dsploit.core;

import android.app.IntentService;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.app.NotificationCompat;

import java.util.Scanner;

import it.evilsocket.dsploit.MainActivity;
import it.evilsocket.dsploit.R;
import it.evilsocket.dsploit.net.Network;
import it.evilsocket.dsploit.net.Target;
import it.evilsocket.dsploit.net.metasploit.MsfExploit;
import it.evilsocket.dsploit.plugins.ExploitFinder;
import it.evilsocket.dsploit.plugins.VulnerabilityFinder;
import it.evilsocket.dsploit.tools.NMap;

/**
 * Service for attack multiple targets in background
 */
public class MultiAttackService extends IntentService {

  public final static String MULTI_ACTIONS = "MultiAttackService.data.actions";
  public final static String MULTI_TARGETS = "MultiAttackService.data.targets";

  private static final int NOTIFICATION_ID = 2;
  private static final int CANCEL_CODE = 1;
  private static final int CLICK_CODE = 2;
  private static final String NOTIFICATION_CANCELLED = "it.evilsocket.dSploit.core.MultiAttackService.CANCELLED";

  private final static int TRACE    = 1;
  private final static int SCAN     = 2;
  private final static int INSPECT  = 4;
  private final static int VULN     = 8;
  private final static int EXPLOIT  = 16;
  private final static int CRACK    = 32;

  private NotificationManager mNotificationManager;
  private NotificationCompat.Builder mBuilder;
  private BroadcastReceiver mReceiver;
  private Intent mContentIntent;
  private boolean mRunning = false;

  public MultiAttackService() {
    super("MultiAttackService");
  }

  private class SingleWorker extends Thread {

    private int tasks;
    private Target target;
    private Child mProcess;

    private class Toggle {
      private boolean mValue;

      public Toggle(boolean startValue) {
        mValue = startValue;
      }

      public boolean getBooleanValue() {
        return mValue;
      }

      public void setBooleanValue(boolean value) {
        mValue = value;
      }
    }

    public SingleWorker(final int tasks, final Target target) {
      this.tasks = tasks;
      this.target = target;
    }

    @Override
    public void run() {
      try {
        if((tasks&TRACE)!=0)
          trace();
        if((tasks&SCAN)!=0)
          scan();
        if((tasks&INSPECT)!=0)
          inspect();
        if((tasks&VULN)!=0)
          vuln(true);
        if((tasks&EXPLOIT)!=0)
          exploit();
        if((tasks&CRACK)!=0)
          crack();
        Logger.debug(target+" done");
      } catch (InterruptedException e) {
        Logger.info(e.getMessage());
      }
    }

    private void trace() {
      // not implemented yet
    }

    private void scan() throws InterruptedException {
      try {
        mProcess = System.getTools().nmap.synScan(target, new NMap.SynScanReceiver() {
          @Override
          public void onPortFound(int port, String protocol) {
            target.addOpenPort(port, Network.Protocol.fromString(protocol));
          }
        }, null);
        mProcess.join();
      } catch (ChildManager.ChildNotStartedException e) {
        Logger.error("cannot start nmap process");
      }
    }

    private void inspect() throws InterruptedException {
      try {
        mProcess = System.getTools().nmap.inpsect(target, new NMap.InspectionReceiver() {
          @Override
          public void onOpenPortFound(int port, String protocol) {
            target.addOpenPort(port, Network.Protocol.fromString(protocol));
          }

          @Override
          public void onServiceFound(int port, String protocol, String service, String version) {
            Target.Port p;

            if (service != null && !service.isEmpty())
              if (version != null && !version.isEmpty())
                p = new Target.Port(port, Network.Protocol.fromString(protocol), service, version);
              else
                p = new Target.Port(port, Network.Protocol.fromString(protocol), service);
            else
              p = new Target.Port(port, Network.Protocol.fromString(protocol));
            target.addOpenPort(p);
          }

          @Override
          public void onOsFound(String os) {
            target.setDeviceOS(os);
          }

          @Override
          public void onDeviceFound(String device) {
            target.setDeviceType(device);
          }
        }, target.hasOpenPorts());

        mProcess.join();
      } catch (ChildManager.ChildNotStartedException e) {
        Logger.error("cannot start nmap process");
      }
    }

    private void vuln(boolean searchVersion) throws InterruptedException {

      if(!target.hasOpenPortsWithService())
        return;

      VulnerabilityFinder finder = null;
      for(Plugin p : System.getPlugins()) {
        if(p.getName() == R.string.vulnerability_finder)
          finder = (VulnerabilityFinder)p;
      }

      if(finder == null)
        return;

      final Toggle endReached = new Toggle(false);

      Thread searcher = finder.search(target, new VulnerabilityFinder.VulnerabilityReceiver() {
        @Override
        public void onVulnsFound(Target.Vulnerability[] vulns) { }

        @Override
        public void onEnd() {
          endReached.setBooleanValue(true);
        }
      }, searchVersion);

      searcher.run();
      if(!endReached.getBooleanValue())
        throw new InterruptedException("vuln scan interrupted");
    }

    private void exploit() throws InterruptedException {
      ExploitFinder finder = null;
      boolean noVersionSearched = false;

      for(Plugin p : System.getPlugins()) {
        if(p.getName() == R.string.exploit_finder)
          finder = (ExploitFinder)p;
      }

      if(finder == null)
        return;

      if(!target.hasVulnerabilities()) {
        noVersionSearched = true;
        vuln(false);
        if(!target.hasVulnerabilities())
          return;
      }

      final Toggle endReached = new Toggle(false);
      ExploitFinder.ExploitFinderReceiver receiver = new ExploitFinder.ExploitFinderReceiver() {
        @Override
        public void onExploitFound() { }

        @Override
        public void onEnd() {
          endReached.setBooleanValue(true);
        }
      };

      Thread searcher = finder.search(target, receiver);
      searcher.run();

      if(!endReached.getBooleanValue())
        throw new InterruptedException("exploit interrupted");

      if(!noVersionSearched && !target.hasExploits()) {
        vuln(false);
        endReached.setBooleanValue(false);
        searcher = finder.search(target,receiver);
        searcher.run();

        if(!endReached.getBooleanValue())
          throw new InterruptedException("exploit interrupted");
      }

      if(System.getMsfRpc() != null && target.hasMsfExploits()) {
        for(MsfExploit e : target.getMsfExploits())
          e.tryLaunch();
      }
    }

    private void crack() {
      // not implemented yet
    }

    public void clean() {
      if(mProcess!=null)
        mProcess.kill();
    }

  }

  private void setupNotification() {
    // get notification manager
    mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    // get notification builder
    mBuilder = new NotificationCompat.Builder(this);
    // create a broadcast receiver to get actions
    // performed on the notification by the user
    mReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Logger.debug("received action: "+action);
        if(action==null)
          return;
        // user cancelled our notification
        if(action.equals(NOTIFICATION_CANCELLED)) {
          mRunning = false;
          stopSelf();
        }
      }
    };
    mContentIntent = null;
    // register our receiver
    registerReceiver(mReceiver,new IntentFilter(NOTIFICATION_CANCELLED));
    // set common notification actions
    mBuilder.setDeleteIntent(PendingIntent.getBroadcast(this, CANCEL_CODE, new Intent(NOTIFICATION_CANCELLED), 0));
    mBuilder.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(), 0));
  }

  /**
   * if mContentIntent is null delete our notification,
   * else assign it to the notification onClick
   */
  private void finishNotification() {
    if(mContentIntent==null){
      Logger.debug("deleting notifications");
      mNotificationManager.cancel(NOTIFICATION_ID);
    } else {
      Logger.debug("assign '"+mContentIntent.toString()+"'to notification");
      mBuilder.setContentIntent(PendingIntent.getActivity(this, CLICK_CODE, mContentIntent, 0))
              .setProgress(0,0,false)
              .setAutoCancel(true)
              .setDeleteIntent(PendingIntent.getActivity(this, 0, new Intent(), 0));
      mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }
    if(mReceiver!=null)
      unregisterReceiver(mReceiver);
    mReceiver             = null;
    mBuilder              = null;
    mNotificationManager  = null;
  }

  @Override
  protected void onHandleIntent(Intent intent) {
    int[] actions,targetsIndex;
    int i=-1;

    //initialize data
    int tasks = 0;

    actions = intent.getIntArrayExtra(MULTI_ACTIONS);
    targetsIndex = intent.getIntArrayExtra(MULTI_TARGETS);

    if(actions == null || targetsIndex == null)
      return;

    mRunning = true;

    //fetch targets
    Target[] targets = new Target[targetsIndex.length];

    for(i =0; i< targetsIndex.length;i++)
      targets[i] = System.getTarget(targetsIndex[i]);

    //fetch tasks
    for(int stringId : actions) {
      switch (stringId) {
        case R.string.trace:
          tasks |=TRACE;
          break;
        case R.string.port_scanner:
          tasks |=SCAN;
          break;
        case R.string.inspector:
          tasks |=INSPECT;
          break;
        case R.string.vulnerability_finder:
          tasks |=VULN;
          break;
        case R.string.exploit_finder:
          tasks |=EXPLOIT;
          break;
        case R.string.login_cracker:
          tasks |=CRACK;
          break;
      }
    }

    Logger.debug("targets: "+targets.length);
    Logger.debug("tasks: "+ tasks);

    setupNotification();

    mBuilder.setContentTitle(getString(R.string.multiple_attack))
            .setSmallIcon(R.drawable.ic_launcher)
            .setProgress(100, 0, true);

    // create and start threads
    final int fTasks = tasks;
    SingleWorker[] threadPool = new SingleWorker[targets.length];

    for(i = 0; i < threadPool.length;i++)
      (threadPool[i] = new SingleWorker(fTasks, targets[i])).start();

    //join them
    try {
      while(i>0&&mRunning) {
        mBuilder.setContentInfo(String.format("%d/%d",targets.length-i, targets.length));
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
        threadPool[--i].join();
      }
      if(mRunning) {
        mBuilder.setContentInfo(String.format("%d/%d",targets.length, targets.length)); // will be notified by finishNotification
        mContentIntent = new Intent(this, MainActivity.class);
      } else {
        throw new InterruptedException("cancelled");
      }
    } catch (InterruptedException e) {
      while(i>=0)
        threadPool[i--].interrupt();
      Logger.debug("interrupted");
    } finally {
      for(SingleWorker w : threadPool) {
        w.clean();
      }
      stopSelf();
      mRunning = false;
    }
  }

  @Override
  public void onDestroy() {
    finishNotification();
    super.onDestroy();
  }
}
