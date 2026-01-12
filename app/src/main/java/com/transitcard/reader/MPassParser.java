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
            // M Pass 잔액 읽기 명령어 (실제 구현 필요)
            byte[] balanceCommand = new byte[]{
                    0x00, (byte) 0xB0, 0x00, 0x04, 0x04
            };

            byte[] response = isoDep.transceive(balanceCommand);

            if (response != null && response.length >= 4) {
                // 잔액 파싱 로직
                return parseBalanceFromResponse(response);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading balance", e);
        }
        return 0;
    }

    private int parseBalanceFromResponse(byte[] response) {
        // 실제 M Pass 잔액 형식에 맞게 파싱
        // 예시: 4바이트를 정수로 변환
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
