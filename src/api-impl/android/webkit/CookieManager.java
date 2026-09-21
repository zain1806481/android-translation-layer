package android.webkit;

import android.atl.ATLLoadedApp;

import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide cookie store mirrored into WebKitGTK when available.
 * NewPipe PoToken WebView path remains disabled (throws).
 */
public class CookieManager {

	private static final CookieManager INSTANCE = new CookieManager();
	private final List<StoredCookie> cookies = new CopyOnWriteArrayList<>();
	private boolean acceptCookie = true;

	public static CookieManager getInstance() {
		// HACK: disable NewPipe's WebView based PoToken generator for now
		if (ATLLoadedApp.getPrimaryApplication().pkg.packageName.equals("org.schabi.newpipe")) {
			throw new RuntimeException("CookieManager not yet fully implemented");
		}
		try { // also handle NewPipe forks which can have a different packagename
			ATLLoadedApp.getPrimaryApplication().loadClass(
			    "org.schabi.newpipe.util.potoken.PoTokenWebView");
			throw new RuntimeException("CookieManager not yet fully implemented");
		} catch (ClassNotFoundException e) {
		}
		return INSTANCE;
	}

	public void removeAllCookies(ValueCallback callback) {
		cookies.clear();
		native_removeAll();
		if (callback != null)
			callback.onReceiveValue(Boolean.TRUE);
	}

	public void removeSessionCookies(ValueCallback callback) {
		for (Iterator<StoredCookie> it = cookies.iterator(); it.hasNext();) {
			StoredCookie c = it.next();
			if (c.expiresMillis <= 0)
				cookies.remove(c);
		}
		if (callback != null)
			callback.onReceiveValue(Boolean.TRUE);
	}

	public void removeExpiredCookie() {
		long now = System.currentTimeMillis();
		for (StoredCookie c : cookies) {
			if (c.expiresMillis > 0 && c.expiresMillis < now)
				cookies.remove(c);
		}
	}

	public void removeAllCookie() {
		removeAllCookies(null);
	}

	public void removeSessionCookie() {
		removeSessionCookies(null);
	}

	public void flush() {}

	public String getCookie(String url) {
		if (url == null || !acceptCookie)
			return null;
		removeExpiredCookie();
		String host;
		String path;
		boolean secure;
		try {
			URI uri = URI.create(url);
			host = uri.getHost();
			path = uri.getPath();
			if (path == null || path.isEmpty())
				path = "/";
			secure = "https".equalsIgnoreCase(uri.getScheme());
		} catch (Exception e) {
			return null;
		}
		if (host == null)
			return null;
		host = host.toLowerCase(Locale.ROOT);
		StringBuilder out = new StringBuilder();
		for (StoredCookie c : cookies) {
			if (!domainMatches(c.domain, host))
				continue;
			if (!pathMatches(c.path, path))
				continue;
			if (c.secure && !secure)
				continue;
			if (out.length() > 0)
				out.append("; ");
			out.append(c.name).append('=').append(c.value);
		}
		return out.length() == 0 ? null : out.toString();
	}

	public void setCookie(String url, String value) {
		if (!acceptCookie || url == null || value == null || value.isEmpty())
			return;
		StoredCookie parsed = StoredCookie.parse(url, value);
		if (parsed == null)
			return;
		for (StoredCookie existing : cookies) {
			if (existing.name.equals(parsed.name) && existing.domain.equals(parsed.domain)
			    && existing.path.equals(parsed.path)) {
				cookies.remove(existing);
				break;
			}
		}
		cookies.add(parsed);
		native_setCookie(url, value);
	}

	public void setAcceptCookie(boolean accept) {
		acceptCookie = accept;
	}

	public boolean acceptCookie() {
		return acceptCookie;
	}

	public boolean acceptThirdPartyCookies(WebView webview) {
		return false;
	}

	public void setAcceptThirdPartyCookies(WebView webView, boolean accept) {}

	public static void setAcceptFileSchemeCookies(boolean accept) {}

	private static boolean domainMatches(String cookieDomain, String host) {
		if (cookieDomain == null || host == null)
			return false;
		String d = cookieDomain.startsWith(".") ? cookieDomain.substring(1) : cookieDomain;
		d = d.toLowerCase(Locale.ROOT);
		return host.equals(d) || host.endsWith("." + d);
	}

	private static boolean pathMatches(String cookiePath, String urlPath) {
		if (cookiePath == null || cookiePath.isEmpty())
			cookiePath = "/";
		if (urlPath == null || urlPath.isEmpty())
			urlPath = "/";
		if (!urlPath.startsWith(cookiePath))
			return false;
		return cookiePath.endsWith("/") || urlPath.length() == cookiePath.length()
		    || urlPath.charAt(cookiePath.length()) == '/';
	}

	private static final class StoredCookie {
		final String name;
		final String value;
		final String domain;
		final String path;
		final boolean secure;
		final long expiresMillis;

		StoredCookie(String name, String value, String domain, String path, boolean secure, long expiresMillis) {
			this.name = name;
			this.value = value;
			this.domain = domain;
			this.path = path;
			this.secure = secure;
			this.expiresMillis = expiresMillis;
		}

		static StoredCookie parse(String url, String setCookie) {
			String host;
			String defaultPath;
			try {
				URI uri = URI.create(url);
				host = uri.getHost();
				defaultPath = uri.getPath();
				if (defaultPath == null || defaultPath.isEmpty())
					defaultPath = "/";
				else {
					int slash = defaultPath.lastIndexOf('/');
					defaultPath = slash <= 0 ? "/" : defaultPath.substring(0, slash + 1);
				}
			} catch (Exception e) {
				return null;
			}
			if (host == null)
				return null;
			String[] parts = setCookie.split(";");
			if (parts.length == 0)
				return null;
			String nv = parts[0].trim();
			int eq = nv.indexOf('=');
			if (eq <= 0)
				return null;
			String name = nv.substring(0, eq).trim();
			String value = nv.substring(eq + 1).trim();
			String domain = host.toLowerCase(Locale.ROOT);
			String path = defaultPath;
			boolean secure = false;
			long expires = 0;
			for (int i = 1; i < parts.length; i++) {
				String p = parts[i].trim();
				int peq = p.indexOf('=');
				String key = (peq < 0 ? p : p.substring(0, peq)).trim().toLowerCase(Locale.ROOT);
				String val = peq < 0 ? "" : p.substring(peq + 1).trim();
				if ("domain".equals(key) && !val.isEmpty()) {
					domain = val.toLowerCase(Locale.ROOT);
					if (domain.startsWith("."))
						domain = domain.substring(1);
				} else if ("path".equals(key) && !val.isEmpty()) {
					path = val;
				} else if ("secure".equals(key)) {
					secure = true;
				} else if ("max-age".equals(key)) {
					try {
						long sec = Long.parseLong(val);
						expires = System.currentTimeMillis() + sec * 1000L;
					} catch (NumberFormatException ignored) {}
				}
			}
			return new StoredCookie(name, value, domain, path, secure, expires);
		}
	}

	private static native void native_setCookie(String url, String value);
	private static native void native_removeAll();
}
