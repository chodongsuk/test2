package com.transitcard.reader;

import android.nfc.tech.IsoDep;

public interface CardParser {
    TransitCardData parse(IsoDep isoDep, byte[] cardId);
}
