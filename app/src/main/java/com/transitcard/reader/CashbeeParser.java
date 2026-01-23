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
            // Step 1: Select file (DF)
            byte[] selectCommand = new byte[]{
                    (byte) 0x00, (byte) 0xA4, (byte) 0x00, (byte) 0x00,
                    (byte) 0x02, (byte) 0x42, (byte) 0x00
            };
            byte[] selectResponse = isoDep.transceive(selectCommand);

            // Check if select was successful
            if (selectResponse.length < 2 ||
                selectResponse[selectResponse.length - 2] != (byte) 0x90 ||
                selectResponse[selectResponse.length - 1] != (byte) 0x00) {
                Log.e(TAG, "File selection failed");
                return 0;
            }

            // Step 2: Read balance
            byte[] balanceCommand = new byte[]{
                    (byte) 0x90, (byte) 0x4C, (byte) 0x00, (byte) 0x00,
                    (byte) 0x04
            };
            byte[] response = isoDep.transceive(balanceCommand);

            if (response.length >= 6) {
                // Balance is stored in first 4 bytes (big-endian)
                // balance = balance[0] * 256^3 + balance[1] * 256^2 + balance[2] * 256 + balance[3]
                int balance = ((response[0] & 0xFF) << 24) |
                             ((response[1] & 0xFF) << 16) |
                             ((response[2] & 0xFF) << 8) |
                             (response[3] & 0xFF);
                return balance;
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
