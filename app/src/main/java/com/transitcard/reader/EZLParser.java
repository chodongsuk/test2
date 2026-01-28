package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class EZLParser implements CardParser {
    private static final String TAG = "EZLParser";

    // EZL 전용 명령어
    private static final byte[] CMD_SELECT_SECONDARY_AID = {
            0x00, (byte) 0xA4, 0x04, 0x00, 0x07,
            (byte) 0xD4, 0x10, 0x00, 0x00, 0x14, 0x00, 0x01,
            0x00
    };
    private static final byte[] CMD_BALANCE = {(byte) 0x90, 0x4C, 0x00, 0x00, 0x04};

    // SFI 4 사용 (모든 거래 정보)
    private static final byte P2_BALANCE_RECORD = 0x24;  // SFI 4
    private static final byte LE_RECORD = 0x1A;          // 26 bytes

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // EZL은 Secondary AID 선택 필요
            selectSecondaryAid(isoDep);

            int balance = readBalance(isoDep);
            String cardNumber = readCardNumber(isoDep);
            if (cardNumber == null || cardNumber.isEmpty()) {
                cardNumber = bytesToHex(cardId);
            }
            List<Transaction> transactions = readTransactionHistory(isoDep);

            return new TransitCardData(CardType.EZL, cardNumber, balance, transactions);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing EZL card", e);
            return new TransitCardData(CardType.EZL, bytesToHex(cardId), 0, new ArrayList<>());
        }
    }

    // ===== Secondary AID 선택 =====

    private byte[] secondaryAidResponse = null;  // Secondary AID 응답 저장

    private boolean selectSecondaryAid(IsoDep isoDep) {
        try {
            byte[] response = isoDep.transceive(CMD_SELECT_SECONDARY_AID);
            Log.d(TAG, "Secondary AID response: " + bytesToHex(response));

            // 응답 저장 (카드번호 추출용)
            if (isSuccess(response)) {
                secondaryAidResponse = response;
                Log.d(TAG, "Secondary AID selected successfully");
            } else {
                Log.w(TAG, "Secondary AID selection failed");
            }

            return isSuccess(response);
        } catch (Exception e) {
            Log.e(TAG, "selectSecondaryAid error", e);
            return false;
        }
    }

    // ===== 잔액 읽기 =====

    private int readBalance(IsoDep isoDep) {
        try {
            byte[] response = isoDep.transceive(CMD_BALANCE);
            Log.d(TAG, "Balance response: " + bytesToHex(response));

            if (response.length >= 6 && isSuccess(response)) {
                int balance = ((response[0] & 0xFF) << 24) |
                        ((response[1] & 0xFF) << 16) |
                        ((response[2] & 0xFF) << 8) |
                        (response[3] & 0xFF);
                Log.i(TAG, "Balance: " + balance + "원");
                return balance;
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "readBalance error", e);
            return 0;
        }
    }

    // ===== 카드번호 읽기 =====

    private String readCardNumber(IsoDep isoDep) {
        Log.d(TAG, "=== readCardNumber ===");

        // Secondary AID 응답에서 카드번호 추출
        if (secondaryAidResponse != null) {
            String cardNum = extractCardNumber(secondaryAidResponse);
            if (cardNum != null) {
                Log.i(TAG, "Card number found from Secondary AID");
                return cardNum;
            }
        } else {
            Log.w(TAG, "Secondary AID response is null");
        }

        Log.w(TAG, "Card number not found");
        return null;
    }

    /**
     * 카드번호 추출
     * Secondary AID 응답 (FCI)의 offset 8에서 8바이트 BCD 추출
     */
    private String extractCardNumber(byte[] data) {
        if (data == null || data.length < 10) {
            Log.d(TAG, "extractCardNumber: data too short");
            return null;
        }

        int length = data.length - 2;  // Status Word 제외

        // Status Word 확인
        int sw1 = data[data.length - 2] & 0xFF;
        int sw2 = data[data.length - 1] & 0xFF;
        Log.d(TAG, "extractCardNumber: SW=" + String.format("%02X%02X", sw1, sw2));

        if (sw1 != 0x90 || sw2 != 0x00) {
            Log.d(TAG, "extractCardNumber: Invalid status word");
            return null;
        }

        // FCI 응답 확인 (6F 태그)
        if (length >= 16 && (data[0] & 0xFF) == 0x6F) {
            Log.d(TAG, "extractCardNumber: FCI response detected");
            // Offset 8에서 BCD 카드번호 추출 및 포맷팅
            String cardNum = formatBcdCardNumber(data, 8, 8);
            if (isValidCardNumber(cardNum)) {
                Log.i(TAG, "Card number found at FCI offset 8: " + cardNum);
                return cardNum;
            }
        } else {
            Log.d(TAG, "extractCardNumber: Not a valid FCI response (tag=0x" +
                    String.format("%02X", data[0] & 0xFF) + ")");
        }

        return null;
    }

    // ===== 거래내역 읽기 =====

    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();
        Log.d(TAG, "=== readTransactionHistory ===");

        // SFI 4에서 모든 거래 읽기
        for (int record = 1; record <= 10; record++) {
            try {
                byte[] cmd = {0x00, (byte) 0xB2, (byte) record, P2_BALANCE_RECORD, LE_RECORD};
                byte[] response = isoDep.transceive(cmd);

                Log.i(TAG, "SFI4 Record " + record + ": " + bytesToHex(response));

                Transaction tx = parseBalanceRecord(response);

                if (tx != null) {
                    transactions.add(tx);
                    Log.i(TAG, String.format("Transaction: %s | %s | %d원 | 잔액: %d원",
                            tx.getDate(), tx.getLocation(), tx.getAmount(), tx.getBalanceAfter()));
                } else if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    if (sw1 == 0x6A) break;  // No more records
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading record " + record, e);
                break;
            }
        }

        Log.i(TAG, "Found " + transactions.size() + " transactions");
        return transactions;
    }

    /**
     * 거래내역 파싱
     * Offset 0:     거래 타입 (0x01=사용, 0x02=충전)
     * Offset 4-5:   잔액 (2 bytes, Big Endian)
     * Offset 12-13: 거래 금액 (2 bytes, Big Endian)
     */
    private Transaction parseBalanceRecord(byte[] data) {
        if (data == null || data.length < 20) return null;

        int sw1 = data[data.length - 2] & 0xFF;
        int sw2 = data[data.length - 1] & 0xFF;
        if (sw1 != 0x90 || sw2 != 0x00) return null;

        int dataLength = data.length - 2;

        // 빈 레코드 체크
        boolean isEmpty = true;
        for (int i = 0; i < Math.min(16, dataLength); i++) {
            if (data[i] != 0 && (data[i] & 0xFF) != 0xFF) {
                isEmpty = false;
                break;
            }
        }
        if (isEmpty) return null;

        try {
            // offset 0: 거래 타입
            int recordType = data[0] & 0xFF;

            // offset 4-5: 거래 후 잔액
            int balance = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);

            // offset 12-13: 거래 금액
            int amount = ((data[12] & 0xFF) << 8) | (data[13] & 0xFF);

            // 유효성 검사
            if (balance < 0 || balance > 500000) return null;
            if (amount < 0 || amount > 500000) return null;

            // 거래 타입 판별
            TransactionType txType;
            String location;

            if (recordType == 0x02) {
                txType = TransactionType.CHARGE;
                location = "충전";
            } else {
                txType = TransactionType.USE;
                location = "사용";
            }

            // 날짜는 카드에 없음
            return new Transaction("", location, amount, balance, txType);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing balance record", e);
            return null;
        }
    }

    // ===== 유틸리티 메서드 =====

    private boolean isSuccess(byte[] response) {
        if (response == null || response.length < 2) return false;
        return (response[response.length - 2] & 0xFF) == 0x90 &&
                (response[response.length - 1] & 0xFF) == 0x00;
    }

    /**
     * 카드번호 유효성 검사
     */
    private boolean isValidCardNumber(String cardNum) {
        if (cardNum == null) return false;
        String digits = cardNum.replace(" ", "");
        if (digits.length() < 16) return false;
        // 숫자만 있는지 확인
        for (char c : digits.toCharArray()) {
            if (c < '0' || c > '9') return false;
        }
        // 모두 0인지 확인
        for (char c : digits.toCharArray()) {
            if (c != '0') return true;
        }
        return false;
    }

    /**
     * BCD 형식 카드번호 포맷팅
     */
    private String formatBcdCardNumber(byte[] data, int offset, int len) {
        if (offset + len > data.length) {
            Log.w(TAG, "formatBcdCardNumber: offset + len exceeds data length");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        Log.d(TAG, "formatBcdCardNumber: offset=" + offset + ", len=" + len);

        for (int i = 0; i < len; i++) {
            int high = (data[offset + i] >> 4) & 0x0F;
            int low = data[offset + i] & 0x0F;

            Log.d(TAG, "  Byte[" + i + "]=0x" + String.format("%02X", data[offset + i]) +
                    " -> high=" + high + ", low=" + low);

            // BCD 유효성 검사
            if (high > 9 || low > 9) {
                Log.w(TAG, "formatBcdCardNumber: Invalid BCD at byte " + i);
                return null;
            }

            sb.append(high).append(low);
        }

        String raw = sb.toString();
        Log.d(TAG, "formatBcdCardNumber: raw=" + raw + " (length=" + raw.length() + ")");

        if (raw.length() >= 16) {
            String formatted = raw.substring(0, 4) + " " + raw.substring(4, 8) + " " +
                    raw.substring(8, 12) + " " + raw.substring(12, 16);
            Log.i(TAG, "formatBcdCardNumber: formatted=" + formatted);
            return formatted;
        }

        Log.w(TAG, "formatBcdCardNumber: raw length < 16, cannot format");
        return null;
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}