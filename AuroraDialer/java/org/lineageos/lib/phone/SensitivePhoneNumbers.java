
package org.lineageos.lib.phone;

import android.content.Context;
import org.lineageos.lib.phone.spn.Item;
import java.util.ArrayList;

public class SensitivePhoneNumbers {
    private static SensitivePhoneNumbers sInstance;

    private SensitivePhoneNumbers() {}

    public static synchronized SensitivePhoneNumbers getInstance() {
        if (sInstance == null) {
            sInstance = new SensitivePhoneNumbers();
        }
        return sInstance;
    }

    public boolean isSensitiveNumber(Context context, String number, int subId) {
        return false;
    }

    public ArrayList<Item> getSensitivePnInfosForMcc(String mcc) {
        return new ArrayList<>();
    }
}
