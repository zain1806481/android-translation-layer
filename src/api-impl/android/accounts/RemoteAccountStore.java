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
 *   Default: looks for {@code atl-account-helper} on PATH.
 *
 * Protocol: listAccounts, addAccount, removeAccount, getPassword,
 * getUserData, setUserData, peekAuthToken, setAuthToken, invalidateAuthToken.
 * Helper backends (local / waydroid / adb) live outside ATL.
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
				String resp = call("addAccount",
					"\"name\":" + jsonString(account.name)
						+ ",\"type\":" + jsonString(account.type)
						+ ",\"password\":" + jsonString(password != null ? password : ""));
				return resp != null && resp.contains("\"ok\":true");
			} catch (Exception e) {
				Log.w(TAG, "addAccount failed: " + e);
				return false;
			}
		}
	}

	@Override
	public boolean removeAccountExplicitly(Account account) {
		if (account == null || account.name == null || account.type == null) {
			return false;
		}
		synchronized (lock) {
			try {
				String resp = call("removeAccount",
					"\"name\":" + jsonString(account.name)
						+ ",\"type\":" + jsonString(account.type));
				return resp != null && resp.contains("\"ok\":true")
					&& resp.contains("\"removed\":true");
			} catch (Exception e) {
				Log.w(TAG, "removeAccount failed: " + e);
				return false;
			}
		}
	}

	@Override
	public String getPassword(Account account) {
		if (account == null || account.name == null || account.type == null) {
			return null;
		}
		synchronized (lock) {
			try {
				String resp = call("getPassword",
					"\"name\":" + jsonString(account.name)
						+ ",\"type\":" + jsonString(account.type));
				if (resp == null || !resp.contains("\"ok\":true")) {
					return null;
				}
				return extractResultString(resp, "password");
			} catch (Exception e) {
				Log.w(TAG, "getPassword failed: " + e);
				return null;
			}
		}
	}

	@Override
	public String getUserData(Account account, String key) {
		if (account == null || account.name == null || account.type == null || key == null) {
			return null;
		}
		synchronized (lock) {
			try {
				String resp = call("getUserData",
					"\"name\":" + jsonString(account.name)
						+ ",\"type\":" + jsonString(account.type)
						+ ",\"key\":" + jsonString(key));
				if (resp == null || !resp.contains("\"ok\":true")) {
					return null;
				}
				return extractResultString(resp, "value");
			} catch (Exception e) {
				Log.w(TAG, "getUserData failed: " + e);
				return null;
			}
		}
	}

	@Override
	public void setUserData(Account account, String key, String value) {
		if (account == null || account.name == null || account.type == null || key == null) {
			return;
		}
		synchronized (lock) {
			try {
				String valueJson = value == null ? "null" : jsonString(value);
				call("setUserData",
					"\"name\":" + jsonString(account.name)
						+ ",\"type\":" + jsonString(account.type)
						+ ",\"key\":" + jsonString(key)
						+ ",\"value\":" + valueJson);
			} catch (Exception e) {
				Log.w(TAG, "setUserData failed: " + e);
			}
		}
	}

	@Override
	public String peekAuthToken(Account account, String authTokenType) {
		if (account == null || account.name == null || account.type == null || authTokenType == null) {
			return null;
		}
		synchronized (lock) {
			try {
				String resp = call("peekAuthToken",
					"\"name\":" + jsonString(account.name)
						+ ",\"type\":" + jsonString(account.type)
						+ ",\"authTokenType\":" + jsonString(authTokenType));
				if (resp == null || !resp.contains("\"ok\":true")) {
					return null;
				}
				return extractResultString(resp, "authToken");
			} catch (Exception e) {
				Log.w(TAG, "peekAuthToken failed: " + e);
				return null;
			}
		}
	}

	@Override
	public void setAuthToken(Account account, String authTokenType, String authToken) {
		if (account == null || account.name == null || account.type == null || authTokenType == null) {
			return;
		}
		synchronized (lock) {
			try {
				String tokenJson = authToken == null ? "null" : jsonString(authToken);
				call("setAuthToken",
					"\"name\":" + jsonString(account.name)
						+ ",\"type\":" + jsonString(account.type)
						+ ",\"authTokenType\":" + jsonString(authTokenType)
						+ ",\"authToken\":" + tokenJson);
			} catch (Exception e) {
				Log.w(TAG, "setAuthToken failed: " + e);
			}
		}
	}

	@Override
	public void invalidateAuthToken(String accountType, String authToken) {
		if (authToken == null) {
			return;
		}
		synchronized (lock) {
			try {
				String typeJson = accountType == null ? "null" : jsonString(accountType);
				call("invalidateAuthToken",
					"\"accountType\":" + typeJson
						+ ",\"authToken\":" + jsonString(authToken));
			} catch (Exception e) {
				Log.w(TAG, "invalidateAuthToken failed: " + e);
			}
		}
	}

	private Account[] listAccounts(String type) {
		synchronized (lock) {
			try {
				String typeJson = type == null ? "null" : jsonString(type);
				String resp = call("listAccounts", "\"type\":" + typeJson);
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

	private String call(String method, String paramsBody) throws Exception {
		int id = nextId++;
		StringBuilder req = new StringBuilder();
		req.append("{\"v\":1,\"type\":\"request\",\"id\":").append(id);
		req.append(",\"method\":").append(jsonString(method));
		req.append(",\"params\":{").append(paramsBody).append("}}");
		return transact(req.toString());
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

	/** Prefer value inside {@code "result":{...}} when present. */
	private static String extractResultString(String json, String field) {
		int resultIdx = json.indexOf("\"result\"");
		if (resultIdx >= 0) {
			int brace = json.indexOf('{', resultIdx);
			if (brace >= 0) {
				int end = matchingBrace(json, brace);
				if (end > brace) {
					String value = extractStringField(json.substring(brace, end + 1), field);
					if (value != null || json.substring(brace, end + 1).contains("\"" + field + "\":null")) {
						return value;
					}
				}
			}
		}
		return extractStringField(json, field);
	}

	private static int matchingBrace(String s, int openIdx) {
		int depth = 0;
		boolean inStr = false;
		for (int i = openIdx; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inStr) {
				if (c == '\\' && i + 1 < s.length()) {
					i++;
				} else if (c == '"') {
					inStr = false;
				}
				continue;
			}
			if (c == '"') {
				inStr = true;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
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
		int p = colon + 1;
		while (p < obj.length() && Character.isWhitespace(obj.charAt(p))) {
			p++;
		}
		if (p < obj.length() && obj.startsWith("null", p)) {
			return null;
		}
		int q1 = obj.indexOf('"', colon + 1);
		if (q1 < 0) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int idx = q1 + 1; idx < obj.length(); idx++) {
			char c = obj.charAt(idx);
			if (c == '\\' && idx + 1 < obj.length()) {
				sb.append(obj.charAt(++idx));
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
