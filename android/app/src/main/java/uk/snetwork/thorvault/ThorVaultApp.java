package uk.snetwork.thorvault;
import android.app.Application;
public final class ThorVaultApp extends Application {
  @Override public void onCreate() {
    super.onCreate();
    SyncScheduler.ensurePeriodic(this);
  }
}
