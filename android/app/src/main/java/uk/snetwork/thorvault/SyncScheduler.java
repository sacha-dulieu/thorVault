package uk.snetwork.thorvault;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public final class SyncScheduler {
  private SyncScheduler() {}
  private static Constraints online() {
    return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
  }
  public static void enqueueNow(Context context) {
    OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(VaultSyncWorker.class)
        .setConstraints(online()).build();
    WorkManager.getInstance(context).enqueueUniqueWork(
        "thorvault-sync-now", ExistingWorkPolicy.REPLACE, work);
  }
  public static void ensurePeriodic(Context context) {
    PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
        VaultSyncWorker.class, 15, TimeUnit.MINUTES).setConstraints(online()).build();
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "thorvault-sync-periodic", ExistingPeriodicWorkPolicy.KEEP, work);
  }
}
