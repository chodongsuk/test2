package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;

public class SeoulCityPassParser implements CardParser {
    private static final String TAG = "SeoulCityPassParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // Seoul City Pass는 관광객용 통합 교통카드
            // 티머니 기반 시스템 사용

            int balance = readBalance(isoDep);

            return new TransitCardData(
                    CardType.SEOUL_CITY_PASS,
                    bytesToHex(cardId),
                    balance,
                    new ArrayList<>()
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Seoul City Pass card", e);
            return null;
        }
    }

    private int readBalance(IsoDep isoDep) {
        try {
            // Seoul City Pass 잔액 읽기
            // 티머니와 유사한 프로토콜 사용
            byte[] balanceCommand = new byte[]{
                    0x00, (byte) 0xB0, 0x00, 0x04, 0x04
            };

            byte[] response = isoDep.transceive(balanceCommand);

            if (response != null && response.length >= 4) {
                return parseBalanceFromResponse(response);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading balance", e);
        }
        return 0;
    }

    private int parseBalanceFromResponse(byte[] response) {
        // 티머니 호환 잔액 형식 파싱
        if (response.length >= 4) {
            return ((response[0] & 0xFF) << 24) |
                   ((response[1] & 0xFF) << 16) |
                   ((response[2] & 0xFF) << 8) |
                   (response[3] & 0xFF);
        }
        return 0;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
