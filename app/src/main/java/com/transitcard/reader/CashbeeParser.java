package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class CashbeeParser implements CardParser {
    private static final String TAG = "CashbeeParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            int balance = readBalance(isoDep);
            List<Transaction> transactions = readTransactionHistory(isoDep);

            return new TransitCardData(
                    CardType.CASHBEE,
                    bytesToHex(cardId),
                    balance,
                    transactions
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Cashbee card", e);
            return new TransitCardData(
                    CardType.CASHBEE,
                    bytesToHex(cardId),
                    0,
                    new ArrayList<>()
            );
        }
    }

    private int readBalance(IsoDep isoDep) {
        try {
            byte[] command = new byte[]{
                    (byte) 0x90, (byte) 0xB0,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x04
            };

            byte[] response = isoDep.transceive(command);
            if (response.length >= 6) {
                return ByteBuffer.wrap(response, 0, 4).getInt();
            } else {
                return 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading balance", e);
            return 0;
        }
    }

    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        // Similar to T-money but with Cashbee-specific format
        return new ArrayList<>();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
