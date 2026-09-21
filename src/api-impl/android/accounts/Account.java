package android.accounts;

import android.os.Parcel;
import android.os.Parcelable;

public class Account implements Parcelable {
	public final String name;
	public final String type;

	public Account(String name, String type) {
		if (name == null) {
			throw new IllegalArgumentException("name is null");
		}
		if (type == null) {
			throw new IllegalArgumentException("type is null");
		}
		this.name = name;
		this.type = type;
	}

	public Account(Parcel in) {
		this.name = in.readString();
		this.type = in.readString();
	}

	@Override
	public String toString() {
		return "Account {name=" + name + ", type=" + type + "}";
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Account)) {
			return false;
		}
		Account other = (Account) o;
		return name.equals(other.name) && type.equals(other.type);
	}

	@Override
	public int hashCode() {
		return name.hashCode() + type.hashCode();
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeString(name);
		dest.writeString(type);
	}

	public static final Creator<Account> CREATOR = new Creator<Account>() {
		@Override
		public Account createFromParcel(Parcel source) {
			return new Account(source);
		}

		@Override
		public Account[] newArray(int size) {
			return new Account[size];
		}
	};
}
