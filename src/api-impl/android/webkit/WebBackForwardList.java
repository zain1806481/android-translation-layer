package android.webkit;

public class WebBackForwardList implements java.io.Serializable {
	WebBackForwardList() {}

	public int getSize() {
		return 0;
	}

	public int getCurrentIndex() {
		return -1;
	}

	public WebHistoryItem getCurrentItem() {
		return null;
	}

	public WebHistoryItem getItemAtIndex(int index) {
		return null;
	}
}
