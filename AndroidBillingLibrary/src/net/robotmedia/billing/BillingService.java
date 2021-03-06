/*   Copyright 2011 Robot Media SL (http://www.robotmedia.net)
*
*   Licensed under the Apache License, Version 2.0 (the "License");
*   you may not use this file except in compliance with the License.
*   You may obtain a copy of the License at
*
*       http://www.apache.org/licenses/LICENSE-2.0
*
*   Unless required by applicable law or agreed to in writing, software
*   distributed under the License is distributed on an "AS IS" BASIS,
*   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*   See the License for the specific language governing permissions and
*   limitations under the License.
*/

package net.robotmedia.billing;

import java.util.LinkedList;

import net.robotmedia.billing.request.CheckBillingSupported;
import net.robotmedia.billing.request.ConfirmNotifications;
import net.robotmedia.billing.request.GetPurchaseInformation;
import net.robotmedia.billing.request.RequestPurchase;
import net.robotmedia.billing.request.RestoreTransactions;
import net.robotmedia.billing.request.BillingRequest;
import net.robotmedia.billing.utils.Compatibility;

import com.android.vending.billing.IMarketBillingService;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class BillingService extends Service implements ServiceConnection {

	private static enum Action {
		CHECK_BILLING_SUPPORTED, CONFIRM_NOTIFICATIONS, GET_PURCHASE_INFORMATION, REQUEST_PURCHASE, RESTORE_TRANSACTIONS,
	}

	private static final String ACTION_MARKET_BILLING_SERVICE = "com.android.vending.billing.MarketBillingService.BIND";
	private static final String EXTRA_DEVELOPER_PAYLOAD = "DEVELOPER_PAYLOAD";

	private static final String EXTRA_ITEM_ID = "ITEM_ID";
	private static final String EXTRA_NONCE = "EXTRA_NONCE";
	private static final String EXTRA_NOTIFY_IDS = "NOTIFY_IDS";
	private static LinkedList<BillingRequest> mPendingRequests = new LinkedList<BillingRequest>();

	private static IMarketBillingService mService;

	public static void checkBillingSupported(Context context) {
		final Intent intent = createIntent(context, Action.CHECK_BILLING_SUPPORTED);
		context.startService(intent);
	}

	public static void confirmNotifications(Context context, String[] notifyIds) {
		final Intent intent = createIntent(context, Action.CONFIRM_NOTIFICATIONS);
		intent.putExtra(EXTRA_NOTIFY_IDS, notifyIds);
		context.startService(intent);
	}

	private static Intent createIntent(Context context, Action action) {
		final String actionString = getActionForIntent(context, action);
		final Intent intent = new Intent(actionString);
		intent.setClass(context, BillingService.class);
		return intent;
	}

	private static final String getActionForIntent(Context context, Action action) {
		return context.getPackageName() + "." + action.toString();
	}

	public static void getPurchaseInformation(Context context, String[] notifyIds, long nonce) {
		final Intent intent = createIntent(context, Action.GET_PURCHASE_INFORMATION);
		intent.putExtra(EXTRA_NOTIFY_IDS, notifyIds);
		intent.putExtra(EXTRA_NONCE, nonce);
		context.startService(intent);
	}

	public static void requestPurchase(Context context, String itemId, String developerPayload) {
		final Intent intent = createIntent(context, Action.REQUEST_PURCHASE);
		intent.putExtra(EXTRA_ITEM_ID, itemId);
		intent.putExtra(EXTRA_DEVELOPER_PAYLOAD, developerPayload);
		context.startService(intent);
	}

	public static void restoreTransations(Context context, long nonce) {
		final Intent intent = createIntent(context, Action.RESTORE_TRANSACTIONS);
		intent.setClass(context, BillingService.class);
		intent.putExtra(EXTRA_NONCE, nonce);
		context.startService(intent);
	}

	private void bindMarketBillingService() {
		try {
			final boolean bindResult = bindService(new Intent(ACTION_MARKET_BILLING_SERVICE), this, Context.BIND_AUTO_CREATE);
			if (!bindResult) {
				Log.e(this.getClass().getSimpleName(), "Could not bind to MarketBillingService");
			}
		} catch (SecurityException e) {
			Log.e(this.getClass().getSimpleName(), "Could not bind to MarketBillingService", e);
		}
	}

	private void checkBillingSupported() {
		final String packageName = getPackageName();
		final CheckBillingSupported request = new CheckBillingSupported(packageName);
		runRequestOrQueue(request);
	}

	private void confirmNotifications(Intent intent) {
		final String packageName = getPackageName();
		final String[] notifyIds = intent.getStringArrayExtra(EXTRA_NOTIFY_IDS);
		final ConfirmNotifications request = new ConfirmNotifications(packageName, notifyIds);
		runRequestOrQueue(request);
	}

	private Action getActionFromIntent(Intent intent) {
		final String actionString = intent.getAction();
		if (actionString == null) {
			return null;
		}
		final String[] split = actionString.split("\\.");
		if (split.length <= 0) {
			return null;
		}
		return Action.valueOf(split[split.length - 1]);
	}

	private void getPurchaseInformation(Intent intent) {
		final String packageName = getPackageName();
		final long nonce = intent.getLongExtra(EXTRA_NONCE, 0);
		final String[] notifyIds = intent.getStringArrayExtra(EXTRA_NOTIFY_IDS);
		final GetPurchaseInformation request = new GetPurchaseInformation(packageName, notifyIds);
		request.setNonce(nonce);
		runRequestOrQueue(request);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder service) {
		mService = IMarketBillingService.Stub.asInterface(service);
		runPendingRequests();
	}

	@Override
	public void onServiceDisconnected(ComponentName name) {
		mService = null;
	}

	// This is the old onStart method that will be called on the pre-2.0
	// platform.  On 2.0 or later we override onStartCommand() so this
	// method will not be called.
	@Override
	public void onStart(Intent intent, int startId) {
	    handleCommand(intent);
	}

	// @Override // Avoid compile errors on pre-2.0
	public int onStartCommand(Intent intent, int flags, int startId) {
	    handleCommand(intent);
	    return Compatibility.START_NOT_STICKY;
	}
	
	private void handleCommand(Intent intent) {
		final Action action = getActionFromIntent(intent);
		if (action == null) {
			return;
		}
		switch (action) {			
		case CHECK_BILLING_SUPPORTED:
			checkBillingSupported();
			break;
		case REQUEST_PURCHASE:
			requestPurchase(intent);
			break;
		case GET_PURCHASE_INFORMATION:
			getPurchaseInformation(intent);
			break;
		case CONFIRM_NOTIFICATIONS:
			confirmNotifications(intent);
			break;
		case RESTORE_TRANSACTIONS:
			restoreTransactions(intent);
		}
	}

	private void requestPurchase(Intent intent) {
		final String packageName = getPackageName();
		final String itemId = intent.getStringExtra(EXTRA_ITEM_ID);
		final String developerPayload = intent.getStringExtra(EXTRA_DEVELOPER_PAYLOAD);
		final RequestPurchase request = new RequestPurchase(packageName, itemId, developerPayload);
		runRequestOrQueue(request);
	}

	private void restoreTransactions(Intent intent) {
		final String packageName = getPackageName();
		final long nonce = intent.getLongExtra(EXTRA_NONCE, 0);
		final RestoreTransactions request = new RestoreTransactions(packageName);
		request.setNonce(nonce);
		runRequestOrQueue(request);
	}

	private void runPendingRequests() {
		BillingRequest request;
		while ((request = mPendingRequests.peek()) != null) {
			if (mService != null) {
				runRequest(request);
				mPendingRequests.remove();
			}
		}
	}

	private void runRequest(BillingRequest request) {
		try {
			final long requestId = request.run(mService);
			BillingController.onRequestSent(requestId, request);
		} catch (RemoteException e) {
			Log.w(this.getClass().getSimpleName(), "Remote billing service crashed");
			// TODO: Retry?
		}
	}

	private void runRequestOrQueue(BillingRequest request) {
		if (mService == null) {
			mPendingRequests.add(request);
			bindMarketBillingService();
			return;
		}
		runRequest(request);
	}

}
