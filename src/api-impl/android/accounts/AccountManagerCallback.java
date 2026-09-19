package android.accounts;

/**
 * Callback for async AccountManager operations (stub surface for app compatibility).
 */
public interface AccountManagerCallback<V> {
	void run(AccountManagerFuture<V> future);
}
