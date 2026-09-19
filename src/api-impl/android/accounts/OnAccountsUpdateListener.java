package android.accounts;

/**
 * Listener for account set changes. ATL dispatches on the calling thread
 * unless a Handler was supplied to {@link AccountManager#addOnAccountsUpdatedListener}.
 */
public interface OnAccountsUpdateListener {
	void onAccountsUpdated(Account[] accounts);
}
