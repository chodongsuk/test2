package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;

public class KoreaTourCardParser implements CardParser {
    private static final String TAG = "KoreaTourCardParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // Korea Tour Card는 외국인 관광객용 전국 호환 교통카드
            // 티머니 플랫폼 기반

            int balance = readBalance(isoDep);

            return new TransitCardData(
                    CardType.KOREA_TOUR_CARD,
                    bytesToHex(cardId),
                    balance,
                    new ArrayList<>()
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Korea Tour Card", e);
            return null;
        }
    }

    private int readBalance(IsoDep isoDep) {
        try {
            Log.d(TAG, "Starting Korea Tour Card balance read (AID already selected)...");

            // Korea Tour Card balance command: 90 4C 00 00 04 (T-money compatible)
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
                Log.d(TAG, "Balance SW: " + String.format("%02X%02X", sw1, sw2));

                if (sw1 == 0x90 && sw2 == 0x00) {
                    // Balance is stored in first 4 bytes (big-endian)
                    int balance = ((response[0] & 0xFF) << 24) |
                                 ((response[1] & 0xFF) << 16) |
                                 ((response[2] & 0xFF) << 8) |
                                 (response[3] & 0xFF);
                    Log.i(TAG, "Parsed balance: " + balance + " won");
                    return balance;
                } else {
                    Log.e(TAG, "Balance command failed with SW: " + String.format("%02X%02X", sw1, sw2));
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

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
