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
            Log.d(TAG, "Starting EZL/Cashbee balance read (AID already selected by NFCReader)...");

            // AID is already selected by NFCReader, directly read balance
            byte[] balanceCommand = new byte[]{
                    (byte) 0x90, (byte) 0x4C, (byte) 0x00, (byte) 0x00,
                    (byte) 0x04
            };
            Log.d(TAG, "Sending balance command: " + bytesToHex(balanceCommand));
            byte[] response = isoDep.transceive(balanceCommand);
            Log.d(TAG, "Balance response (" + response.length + " bytes): " + bytesToHex(response));

            if (response.length >= 6) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "Balance SW: " + Integer.toHexString(sw1) + " " + Integer.toHexString(sw2));

                if (sw1 == 0x90 && sw2 == 0x00) {
                    // Balance is stored in first 4 bytes (big-endian)
                    int balance = ((response[0] & 0xFF) << 24) |
                                 ((response[1] & 0xFF) << 16) |
                                 ((response[2] & 0xFF) << 8) |
                                 (response[3] & 0xFF);
                    Log.d(TAG, "Parsed balance: " + balance + " won");
                    return balance;
                } else {
                    Log.e(TAG, "Balance command failed with SW: " + Integer.toHexString(sw1) + Integer.toHexString(sw2));
                }
            } else {
                Log.e(TAG, "Balance response too short: " + response.length);
            }
            return 0;
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
