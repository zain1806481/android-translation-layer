package android.accounts;

import android.util.Log;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AccountStore that talks JSON-lines to a host helper process (Java 8 safe).
 *
 * Env:
 *   ATL_ACCOUNT_HELPER — absolute path to an executable that reads one JSON
 *   request line on stdin and writes one JSON response line on stdout.
 *   Default: looks for `atl-account-helper` on PATH.
 *
 * Protocol matches NativeAccountStore / bridge docs (listAccounts, addAccount).
 * The helper may wrap Waydroid scaffolding; that glue stays outside ATL.
 */
final class RemoteAccountStore implements AccountStore {
	private static final String TAG = "RemoteAccountStore";
	private final String helperPath;
	private int nextId = 1;
	private final Object lock = new Object();

	RemoteAccountStore() {
		String override = System.getenv("ATL_ACCOUNT_HELPER");
		if (override != null && !override.isEmpty()) {
			helperPath = override;
		} else {
			helperPath = "atl-account-helper";
		}
	}

	@Override
	public Account[] getAccounts() {
		return listAccounts(null);
	}

	@Override
	public Account[] getAccountsByType(String type) {
		return listAccounts(type);
	}

	@Override
	public boolean addAccountExplicitly(Account account, String password) {
		if (account == null || account.name == null || account.type == null) {
			return false;
		}
		synchronized (lock) {
			try {
				int id = nextId++;
				StringBuilder req = new StringBuilder();
				req.append("{\"v\":1,\"type\":\"request\",\"id\":").append(id);
				req.append(",\"method\":\"addAccount\",\"params\":{");
				req.append("\"name\":").append(jsonString(account.name)).append(',');
				req.append("\"type\":").append(jsonString(account.type)).append(',');
				req.append("\"password\":").append(jsonString(password != null ? password : ""));
				req.append("}}");
				String resp = transact(req.toString());
				return resp != null && resp.contains("\"ok\":true");
			} catch (Exception e) {
				Log.w(TAG, "addAccount failed: " + e);
				return false;
			}
		}
	}

	@Override
	public boolean removeAccountExplicitly(Account account) {
		return false;
	}

	@Override
	public String getPassword(Account account) {
		return null;
	}

	@Override
	public String getUserData(Account account, String key) {
		return null;
	}

	@Override
	public void setUserData(Account account, String key, String value) {}

	@Override
	public String peekAuthToken(Account account, String authTokenType) {
		return null;
	}

	@Override
	public void setAuthToken(Account account, String authTokenType, String authToken) {}

	@Override
	public void invalidateAuthToken(String accountType, String authToken) {}

	private Account[] listAccounts(String type) {
		synchronized (lock) {
			try {
				int id = nextId++;
				StringBuilder req = new StringBuilder();
				req.append("{\"v\":1,\"type\":\"request\",\"id\":").append(id);
				req.append(",\"method\":\"listAccounts\",\"params\":{");
				if (type == null) {
					req.append("\"type\":null");
				} else {
					req.append("\"type\":").append(jsonString(type));
				}
				req.append("}}");
				String resp = transact(req.toString());
				if (resp == null || !resp.contains("\"ok\":true")) {
					return new Account[0];
				}
				return parseAccounts(resp);
			} catch (Exception e) {
				Log.w(TAG, "listAccounts failed: " + e);
				return new Account[0];
			}
		}
	}

	private String transact(String requestLine) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(helperPath);
		pb.redirectErrorStream(true);
		Process proc = pb.start();
		try {
			BufferedWriter out = new BufferedWriter(
				new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
			BufferedReader in = new BufferedReader(
				new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
			out.write(requestLine);
			out.write('\n');
			out.flush();
			out.close();
			String resp = in.readLine();
			int code = proc.waitFor();
			if (code != 0) {
				Log.w(TAG, "helper exited " + code + " resp=" + resp);
			}
			return resp;
		} finally {
			proc.destroy();
		}
	}

	private static Account[] parseAccounts(String json) {
		List<Account> out = new ArrayList<Account>();
		int accountsIdx = json.indexOf("\"accounts\"");
		if (accountsIdx < 0) {
			return new Account[0];
		}
		int arrStart = json.indexOf('[', accountsIdx);
		int arrEnd = json.indexOf(']', arrStart);
		if (arrStart < 0 || arrEnd < 0) {
			return new Account[0];
		}
		String arr = json.substring(arrStart + 1, arrEnd);
		int pos = 0;
		while (pos < arr.length()) {
			int objStart = arr.indexOf('{', pos);
			if (objStart < 0) {
				break;
			}
			int objEnd = arr.indexOf('}', objStart);
			if (objEnd < 0) {
				break;
			}
			String obj = arr.substring(objStart, objEnd + 1);
			String name = extractStringField(obj, "name");
			String type = extractStringField(obj, "type");
			if (name != null && type != null) {
				out.add(new Account(name, type));
			}
			pos = objEnd + 1;
		}
		return out.toArray(new Account[0]);
	}

	private static String extractStringField(String obj, String field) {
		String key = "\"" + field + "\"";
		int i = obj.indexOf(key);
		if (i < 0) {
			return null;
		}
		int colon = obj.indexOf(':', i + key.length());
		if (colon < 0) {
			return null;
		}
		int q1 = obj.indexOf('"', colon + 1);
		if (q1 < 0) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int p = q1 + 1; p < obj.length(); p++) {
			char c = obj.charAt(p);
			if (c == '\\' && p + 1 < obj.length()) {
				sb.append(obj.charAt(++p));
			} else if (c == '"') {
				return sb.toString();
			} else {
				sb.append(c);
			}
		}
		return null;
	}

	private static String jsonString(String s) {
		if (s == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder(s.length() + 2);
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' || c == '"') {
				sb.append('\\').append(c);
			} else if (c == '\n') {
				sb.append("\\n");
			} else if (c == '\t') {
				sb.append("\\t");
			} else {
				sb.append(c);
			}
		}
		sb.append('"');
		return sb.toString();
	}
}
