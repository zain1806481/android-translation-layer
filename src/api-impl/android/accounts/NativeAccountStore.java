package android.accounts;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * File-backed local AccountStore (ATL-native).
 *
 * Path: $ATL_ACCOUNT_STORE, else
 * ~/.local/share/android_translation_layer/accounts.json
 *
 * Format: atomic JSON v1 with optional userdata/tokens maps per account.
 * Written via temp file + rename; file mode 0600 when the OS allows.
 * Legacy TSV ({@code _accounts.tsv}) is imported once if the JSON path is empty.
 */
final class NativeAccountStore implements AccountStore {
	private static final String TAG = "NativeAccountStore";
	private final File storeFile;
	private final Object lock = new Object();

	NativeAccountStore() {
		String override = System.getenv("ATL_ACCOUNT_STORE");
		if (override != null && !override.isEmpty()) {
			storeFile = new File(override);
		} else {
			String home = System.getProperty("user.home", ".");
			storeFile = new File(home + "/.local/share/android_translation_layer/accounts.json");
		}
	}

	@Override
	public Account[] getAccounts() {
		synchronized (lock) {
			return toArray(loadUnlocked());
		}
	}

	@Override
	public Account[] getAccountsByType(String type) {
		synchronized (lock) {
			List<AccountRecord> all = loadUnlocked();
			if (type == null) {
				return toArray(all);
			}
			List<AccountRecord> filtered = new ArrayList<AccountRecord>();
			for (AccountRecord r : all) {
				if (type.equals(r.type)) {
					filtered.add(r);
				}
			}
			return toArray(filtered);
		}
	}

	@Override
	public boolean addAccountExplicitly(Account account, String password) {
		if (account == null || account.name == null || account.type == null) {
			return false;
		}
		synchronized (lock) {
			List<AccountRecord> records = loadUnlocked();
			AccountRecord existing = find(records, account);
			if (existing != null) {
				existing.password = password != null ? password : "";
				return saveUnlocked(records);
			}
			records.add(new AccountRecord(account.name, account.type, password != null ? password : ""));
			return saveUnlocked(records);
		}
	}

	@Override
	public boolean removeAccountExplicitly(Account account) {
		if (account == null) {
			return false;
		}
		synchronized (lock) {
			List<AccountRecord> records = loadUnlocked();
			boolean removed = false;
			Iterator<AccountRecord> it = records.iterator();
			while (it.hasNext()) {
				AccountRecord r = it.next();
				if (account.name.equals(r.name) && account.type.equals(r.type)) {
					it.remove();
					removed = true;
				}
			}
			return removed && saveUnlocked(records);
		}
	}

	@Override
	public String getPassword(Account account) {
		synchronized (lock) {
			AccountRecord r = find(loadUnlocked(), account);
			return r != null ? r.password : null;
		}
	}

	@Override
	public String getUserData(Account account, String key) {
		if (key == null) {
			return null;
		}
		synchronized (lock) {
			AccountRecord r = find(loadUnlocked(), account);
			return r != null ? r.userdata.get(key) : null;
		}
	}

	@Override
	public void setUserData(Account account, String key, String value) {
		if (account == null || key == null) {
			return;
		}
		synchronized (lock) {
			List<AccountRecord> records = loadUnlocked();
			AccountRecord r = find(records, account);
			if (r == null) {
				return;
			}
			if (value == null) {
				r.userdata.remove(key);
			} else {
				r.userdata.put(key, value);
			}
			saveUnlocked(records);
		}
	}

	@Override
	public String peekAuthToken(Account account, String authTokenType) {
		if (authTokenType == null) {
			return null;
		}
		synchronized (lock) {
			AccountRecord r = find(loadUnlocked(), account);
			return r != null ? r.tokens.get(authTokenType) : null;
		}
	}

	@Override
	public void setAuthToken(Account account, String authTokenType, String authToken) {
		if (account == null || authTokenType == null) {
			return;
		}
		synchronized (lock) {
			List<AccountRecord> records = loadUnlocked();
			AccountRecord r = find(records, account);
			if (r == null) {
				return;
			}
			if (authToken == null) {
				r.tokens.remove(authTokenType);
			} else {
				r.tokens.put(authTokenType, authToken);
			}
			saveUnlocked(records);
		}
	}

	@Override
	public void invalidateAuthToken(String accountType, String authToken) {
		if (authToken == null) {
			return;
		}
		synchronized (lock) {
			List<AccountRecord> records = loadUnlocked();
			boolean changed = false;
			for (AccountRecord r : records) {
				if (accountType != null && !accountType.equals(r.type)) {
					continue;
				}
				Iterator<Map.Entry<String, String>> it = r.tokens.entrySet().iterator();
				while (it.hasNext()) {
					Map.Entry<String, String> e = it.next();
					if (authToken.equals(e.getValue())) {
						it.remove();
						changed = true;
					}
				}
			}
			if (changed) {
				saveUnlocked(records);
			}
		}
	}

	private static AccountRecord find(List<AccountRecord> records, Account account) {
		if (account == null) {
			return null;
		}
		for (AccountRecord r : records) {
			if (account.name.equals(r.name) && account.type.equals(r.type)) {
				return r;
			}
		}
		return null;
	}

	private List<AccountRecord> loadUnlocked() {
		if (storeFile.exists()) {
			List<AccountRecord> fromJson = loadJson(storeFile);
			if (fromJson != null) {
				return fromJson;
			}
		}
		File legacy = legacyTsvSibling();
		if (legacy != null && legacy.exists()) {
			List<AccountRecord> fromTsv = loadLegacyTsv(legacy);
			if (!fromTsv.isEmpty()) {
				saveUnlocked(fromTsv);
				return fromTsv;
			}
		}
		return new ArrayList<AccountRecord>();
	}

	private File legacyTsvSibling() {
		String name = storeFile.getName();
		File parent = storeFile.getParentFile();
		if (parent == null) {
			return null;
		}
		if (name.endsWith(".json") || name.endsWith(".tsv")) {
			return new File(parent, "_accounts.tsv");
		}
		return new File(parent, "_accounts.tsv");
	}

	private List<AccountRecord> loadJson(File file) {
		try {
			String text = new String(readAll(file), StandardCharsets.UTF_8).trim();
			if (text.isEmpty()) {
				return new ArrayList<AccountRecord>();
			}
			List<AccountRecord> out = new ArrayList<AccountRecord>();
			int arr = text.indexOf("\"accounts\"");
			if (arr < 0) {
				return out;
			}
			int start = text.indexOf('[', arr);
			int end = matchingBracket(text, start);
			if (start < 0 || end < 0) {
				return out;
			}
			String body = text.substring(start + 1, end);
			int pos = 0;
			while (pos < body.length()) {
				int o1 = body.indexOf('{', pos);
				if (o1 < 0) {
					break;
				}
				int o2 = matchingBrace(body, o1);
				if (o2 < 0) {
					break;
				}
				String obj = body.substring(o1, o2 + 1);
				String n = jsonStringField(obj, "name");
				String t = jsonStringField(obj, "type");
				String p = jsonStringField(obj, "password");
				if (n != null && t != null) {
					AccountRecord r = new AccountRecord(n, t, p != null ? p : "");
					r.userdata.putAll(jsonStringMapField(obj, "userdata"));
					r.tokens.putAll(jsonStringMapField(obj, "tokens"));
					out.add(r);
				}
				pos = o2 + 1;
			}
			return out;
		} catch (Exception e) {
			Log.w(TAG, "failed to load JSON store " + file + ": " + e);
			return null;
		}
	}

	private List<AccountRecord> loadLegacyTsv(File file) {
		List<AccountRecord> records = new ArrayList<AccountRecord>();
		try {
			String text = new String(readAll(file), StandardCharsets.UTF_8);
			String[] lines = text.split("\n");
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i].trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\t", 3);
				if (parts.length < 2) {
					continue;
				}
				records.add(new AccountRecord(unescape(parts[0]), unescape(parts[1]),
						parts.length >= 3 ? unescape(parts[2]) : ""));
			}
		} catch (Exception e) {
			Log.w(TAG, "failed to load legacy TSV " + file + ": " + e);
		}
		return records;
	}

	private boolean saveUnlocked(List<AccountRecord> records) {
		File parent = storeFile.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			Log.w(TAG, "could not create " + parent);
			return false;
		}
		File tmp = new File(storeFile.getPath() + ".tmp");
		try {
			StringBuilder sb = new StringBuilder();
			sb.append("{\"v\":1,\"accounts\":[");
			for (int i = 0; i < records.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				AccountRecord r = records.get(i);
				sb.append("{\"name\":").append(jsonString(r.name));
				sb.append(",\"type\":").append(jsonString(r.type));
				sb.append(",\"password\":").append(jsonString(r.password != null ? r.password : ""));
				sb.append(",\"userdata\":").append(jsonMap(r.userdata));
				sb.append(",\"tokens\":").append(jsonMap(r.tokens));
				sb.append('}');
			}
			sb.append("]}\n");
			byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
			FileOutputStream fos = new FileOutputStream(tmp);
			try {
				fos.write(bytes);
				fos.getFD().sync();
			} finally {
				fos.close();
			}
			chmodOwnerOnly(tmp);
			if (!tmp.renameTo(storeFile)) {
				FileOutputStream out = new FileOutputStream(storeFile);
				try {
					out.write(bytes);
					out.getFD().sync();
				} finally {
					out.close();
				}
				tmp.delete();
			}
			chmodOwnerOnly(storeFile);
			return true;
		} catch (Exception e) {
			Log.w(TAG, "failed to save account store " + storeFile + ": " + e);
			tmp.delete();
			return false;
		}
	}

	private static void chmodOwnerOnly(File file) {
		try {
			file.setReadable(false, false);
			file.setWritable(false, false);
			file.setReadable(true, true);
			file.setWritable(true, true);
		} catch (Exception ignored) {
		}
	}

	private static byte[] readAll(File file) throws IOException {
		FileInputStream in = new FileInputStream(file);
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(file.length(), 64));
			byte[] chunk = new byte[4096];
			int n;
			while ((n = in.read(chunk)) >= 0) {
				bos.write(chunk, 0, n);
			}
			return bos.toByteArray();
		} finally {
			in.close();
		}
	}

	private static Account[] toArray(List<AccountRecord> list) {
		Account[] out = new Account[list.size()];
		for (int i = 0; i < list.size(); i++) {
			AccountRecord r = list.get(i);
			out[i] = new Account(r.name, r.type);
		}
		return out;
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

	private static int matchingBracket(String s, int openIdx) {
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
			} else if (c == '[') {
				depth++;
			} else if (c == ']') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static Map<String, String> jsonStringMapField(String obj, String field) {
		Map<String, String> out = new HashMap<String, String>();
		String key = "\"" + field + "\"";
		int i = obj.indexOf(key);
		if (i < 0) {
			return out;
		}
		int colon = obj.indexOf(':', i + key.length());
		if (colon < 0) {
			return out;
		}
		int brace = obj.indexOf('{', colon);
		if (brace < 0) {
			return out;
		}
		int end = matchingBrace(obj, brace);
		if (end < 0) {
			return out;
		}
		String body = obj.substring(brace + 1, end);
		int pos = 0;
		while (pos < body.length()) {
			int q1 = body.indexOf('"', pos);
			if (q1 < 0) {
				break;
			}
			StringBuilder k = new StringBuilder();
			int p = q1 + 1;
			for (; p < body.length(); p++) {
				char c = body.charAt(p);
				if (c == '\\' && p + 1 < body.length()) {
					k.append(body.charAt(++p));
				} else if (c == '"') {
					break;
				} else {
					k.append(c);
				}
			}
			int colon2 = body.indexOf(':', p + 1);
			if (colon2 < 0) {
				break;
			}
			int q2 = body.indexOf('"', colon2 + 1);
			if (q2 < 0) {
				break;
			}
			StringBuilder v = new StringBuilder();
			int p2 = q2 + 1;
			for (; p2 < body.length(); p2++) {
				char c = body.charAt(p2);
				if (c == '\\' && p2 + 1 < body.length()) {
					v.append(body.charAt(++p2));
				} else if (c == '"') {
					break;
				} else {
					v.append(c);
				}
			}
			out.put(k.toString(), v.toString());
			pos = p2 + 1;
		}
		return out;
	}

	private static String jsonStringField(String obj, String field) {
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

	private static String jsonMap(Map<String, String> map) {
		StringBuilder sb = new StringBuilder();
		sb.append('{');
		boolean first = true;
		for (Map.Entry<String, String> e : map.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
		}
		sb.append('}');
		return sb.toString();
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

	private static String unescape(String s) {
		if (s == null || s.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				char n = s.charAt(++i);
				if (n == 't') {
					out.append('\t');
				} else if (n == 'n') {
					out.append('\n');
				} else if (n == '\\') {
					out.append('\\');
				} else {
					out.append(n);
				}
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static final class AccountRecord {
		final String name;
		final String type;
		String password;
		final Map<String, String> userdata = new HashMap<String, String>();
		final Map<String, String> tokens = new HashMap<String, String>();

		AccountRecord(String name, String type, String password) {
			this.name = name;
			this.type = type;
			this.password = password;
		}
	}
}
