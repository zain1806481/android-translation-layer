package android.view;

import android.os.IBinder;

public interface WindowManager {
	public android.view.Display getDefaultDisplay();

	public void addView(View view, ViewGroup.LayoutParams params);

	public void updateViewLayout(View view, ViewGroup.LayoutParams params);

	public void removeView(View view);

	public void removeViewImmediate(View view);

	public class LayoutParams extends ViewGroup.LayoutParams {
		public static final int FLAG_KEEP_SCREEN_ON = 0;
		public static final int FLAG_DIM_BEHIND = 2;
		public static final int FLAG_NOT_FOCUSABLE = 8;

		public float screenBrightness = -1;
		public int softInputMode;
		public int x;
		public int y;
		public int windowAnimations;
		public int flags;
		public float alpha;
		public int type;
		public IBinder token;
		public int format;
		public int layoutInDisplayCutoutMode;
		public String packageName;

		public LayoutParams(int w, int h, int type, int flags, int format) {
			super(w, h);
			this.type = type;
			this.flags = flags;
			this.format = format;
		}

		public LayoutParams() {}

		public void setTitle(CharSequence title) {}

		/** AOSP returns a bitfield of changed fields; apps only need the side effect. */
		public int copyFrom(LayoutParams o) {
			if (o == null)
				return 0;
			width = o.width;
			height = o.height;
			x = o.x;
			y = o.y;
			type = o.type;
			flags = o.flags;
			format = o.format;
			windowAnimations = o.windowAnimations;
			softInputMode = o.softInputMode;
			alpha = o.alpha;
			screenBrightness = o.screenBrightness;
			token = o.token;
			packageName = o.packageName;
			layoutInDisplayCutoutMode = o.layoutInDisplayCutoutMode;
			return 0;
		}
	}
}
