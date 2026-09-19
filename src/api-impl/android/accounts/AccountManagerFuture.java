package android.accounts;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Minimal ATL stub of Android's AccountManagerFuture.
 * Result is already available (no background authenticator work yet).
 */
public interface AccountManagerFuture<V> {
	boolean cancel(boolean mayInterruptIfRunning);

	boolean isCancelled();

	boolean isDone();

	V getResult() throws OperationCanceledException, IOException, AuthenticatorException;

	V getResult(long timeout, TimeUnit unit)
			throws OperationCanceledException, IOException, AuthenticatorException;
}
