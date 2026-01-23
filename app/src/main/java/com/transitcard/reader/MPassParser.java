package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;

public class MPassParser implements CardParser {
    private static final String TAG = "MPassParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // M Pass는 부산 김해 경남 지역 교통카드
            // Mifare 기반으로 티머니와 유사한 구조

            int balance = readBalance(isoDep);

            return new TransitCardData(
                    CardType.MPASS,
                    bytesToHex(cardId),
                    balance,
                    new ArrayList<>()
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing M Pass card", e);
            return null;
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

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
