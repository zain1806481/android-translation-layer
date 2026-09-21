package android.accounts;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * ATL AccountManager with a pluggable AccountStore.
 *
 * Uses NativeAccountStore (atomic JSON file under ATL_ACCOUNT_STORE / XDG).
 * GMS / Google Sign-In are out of scope.
 */
public class AccountManager {
	private static final String TAG = "AccountManager";

	public static final String KEY_ACCOUNT_NAME = "authAccount";
	public static final String KEY_ACCOUNT_TYPE = "accountType";
	public static final String KEY_AUTHTOKEN = "authtoken";
	public static final String KEY_INTENT = "intent";
	public static final String KEY_ERROR_CODE = "errorCode";
	public static final String KEY_ERROR_MESSAGE = "errorMessage";
	public static final String KEY_BOOLEAN_RESULT = "booleanResult";

	private static final Object LOCK = new Object();
	private static AccountManager instance;
	private static AccountStore store;

	private final List<ListenerRecord> listeners = new CopyOnWriteArrayList<ListenerRecord>();

	private AccountManager() {}

	public static AccountManager get(Context context) {
		return getInstance();
	}

	public static AccountManager getInstance() {
		synchronized (LOCK) {
			if (instance == null) {
				store = createStore();
				instance = new AccountManager();
				Log.i(TAG, "AccountManager using store " + store.getClass().getSimpleName());
			}
			return instance;
		}
	}

	private static AccountStore createStore() {
		return new NativeAccountStore();
	}

	public Account[] getAccountsByType(String type) {
		return store.getAccountsByType(type);
	}

	public Account[] getAccounts() {
		return store.getAccounts();
	}

	public boolean addAccountExplicitly(Account account, String password, Bundle extras) {
		boolean ok = store.addAccountExplicitly(account, password);
		if (!ok) {
			Log.w(TAG, "addAccountExplicitly failed for " + account);
		} else {
			if (extras != null) {
				for (String key : extras.keySet()) {
					Object v = extras.get(key);
					if (v != null) {
						store.setUserData(account, key, String.valueOf(v));
					}
				}
			}
			notifyListeners();
		}
		return ok;
	}

	public boolean removeAccountExplicitly(Account account) {
		boolean ok = store.removeAccountExplicitly(account);
		if (ok) {
			notifyListeners();
		}
		return ok;
	}

	public String getPassword(Account account) {
		return store.getPassword(account);
	}

	public String getUserData(Account account, String key) {
		return store.getUserData(account, key);
	}

	public void setUserData(Account account, String key, String value) {
		store.setUserData(account, key, value);
	}

	public String peekAuthToken(Account account, String authTokenType) {
		return store.peekAuthToken(account, authTokenType);
	}

	public void setAuthToken(Account account, String authTokenType, String authToken) {
		store.setAuthToken(account, authTokenType, authToken);
	}

	public void invalidateAuthToken(String accountType, String authToken) {
		store.invalidateAuthToken(accountType, authToken);
	}

	/**
	 * Soft stub: returns a completed future with {@code null} auth token.
	 * Full authenticator binding is out of scope (no GMS).
	 */
	public AccountManagerFuture<Bundle> getAuthToken(Account account, String authTokenType,
			Bundle options, boolean notifyAuthFailure, AccountManagerCallback<Bundle> callback,
			Handler handler) {
		final Bundle result = new Bundle();
		if (account != null) {
			result.putString(KEY_ACCOUNT_NAME, account.name);
			result.putString(KEY_ACCOUNT_TYPE, account.type);
			String tok = peekAuthToken(account, authTokenType);
			if (tok != null) {
				result.putString(KEY_AUTHTOKEN, tok);
			}
		}
		AccountManagerFuture<Bundle> future = new ImmediateFuture<Bundle>(result);
		if (callback != null) {
			dispatchCallback(callback, future, handler);
		}
		return future;
	}

	public void addOnAccountsUpdatedListener(OnAccountsUpdateListener listener,
			Handler handler, boolean updateImmediately) {
		if (listener == null) {
			throw new IllegalArgumentException("listener is null");
		}
		listeners.add(new ListenerRecord(listener, handler));
		if (updateImmediately) {
			dispatch(listener, handler, getAccounts());
		}
	}

	public void removeOnAccountsUpdatedListener(OnAccountsUpdateListener listener) {
		if (listener == null) {
			return;
		}
		for (ListenerRecord r : listeners) {
			if (r.listener == listener) {
				listeners.remove(r);
			}
		}
	}

	private void notifyListeners() {
		Account[] accounts = getAccounts();
		for (ListenerRecord r : listeners) {
			dispatch(r.listener, r.handler, accounts);
		}
	}

	private static void dispatch(final OnAccountsUpdateListener listener, Handler handler,
			final Account[] accounts) {
		if (handler != null) {
			handler.post(new Runnable() {
				@Override
				public void run() {
					listener.onAccountsUpdated(accounts);
				}
			});
		} else {
			listener.onAccountsUpdated(accounts);
		}
	}

	private static <V> void dispatchCallback(final AccountManagerCallback<V> callback,
			final AccountManagerFuture<V> future, Handler handler) {
		Runnable r = new Runnable() {
			@Override
			public void run() {
				callback.run(future);
			}
		};
		if (handler != null) {
			handler.post(r);
		} else {
			r.run();
		}
	}

	private static final class ListenerRecord {
		final OnAccountsUpdateListener listener;
		final Handler handler;

		ListenerRecord(OnAccountsUpdateListener listener, Handler handler) {
			this.listener = listener;
			this.handler = handler;
		}
	}

	private static final class ImmediateFuture<V> implements AccountManagerFuture<V> {
		private final V value;

		ImmediateFuture(V value) {
			this.value = value;
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean isDone() {
			return true;
		}

		@Override
		public V getResult() throws OperationCanceledException, IOException, AuthenticatorException {
			return value;
		}

		@Override
		public V getResult(long timeout, TimeUnit unit)
				throws OperationCanceledException, IOException, AuthenticatorException {
			return value;
		}
	}
}
