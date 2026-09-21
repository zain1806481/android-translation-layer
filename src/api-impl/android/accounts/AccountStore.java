package android.accounts;

/**
 * Pluggable account storage for ATL.
 * NativeAccountStore is the ATL implementation (atomic JSON).
 */
interface AccountStore {
	Account[] getAccounts();

	Account[] getAccountsByType(String type);

	boolean addAccountExplicitly(Account account, String password);

	boolean removeAccountExplicitly(Account account);

	String getPassword(Account account);

	String getUserData(Account account, String key);

	void setUserData(Account account, String key, String value);

	String peekAuthToken(Account account, String authTokenType);

	void setAuthToken(Account account, String authTokenType, String authToken);

	void invalidateAuthToken(String accountType, String authToken);
}
