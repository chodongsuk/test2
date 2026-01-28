package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class TMoneyParser implements CardParser {
    private static final String TAG = "TMoneyParser";

    private static final byte[] CMD_BALANCE = {(byte) 0x90, 0x4C, 0x00, 0x00, 0x04};
    private static final byte[] CMD_CARDINFO = {0x00, (byte) 0xB2, 0x01, 0x14, 0x33};
    private static final byte P2_BALANCE_RECORD = 0x24;  // SFI 4
    private static final byte LE_RECORD = 0x2E;          // 46 bytes

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            int balance = readBalance(isoDep);
            String cardNumber = readCardNumber(isoDep);
            if (cardNumber == null || cardNumber.isEmpty()) {
                cardNumber = bytesToHex(cardId);
            }
            List<Transaction> transactions = readTransactionHistory(isoDep);

            return new TransitCardData(CardType.TMONEY, cardNumber, balance, transactions);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing T-money card", e);
            return new TransitCardData(CardType.TMONEY, bytesToHex(cardId), 0, new ArrayList<>());
        }
    }

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

    private String readCardNumber(IsoDep isoDep) {
        Log.d(TAG, "=== readCardNumber ===");

        try {
            Log.d(TAG, "Trying CARDINFO: " + bytesToHex(CMD_CARDINFO));
            byte[] response = isoDep.transceive(CMD_CARDINFO);
            Log.d(TAG, "CARDINFO response: " + bytesToHex(response));

            String cardNum = extractCardNumber(response);
            if (cardNum != null) return cardNum;
        } catch (Exception e) {
            Log.d(TAG, "CARDINFO failed: " + e.getMessage());
        }

        Log.w(TAG, "Card number not found");
        return null;
    }

    private String extractCardNumber(byte[] data) {
        int length = data.length - 2;  // Status Word 제외

        // FCI 응답 확인 (6F 태그)
        if (length < 16 || (data[0] & 0xFF) != 0x6F) {
            return null;
        }

        // Offset 8에서 BCD 카드번호 추출 및 포맷팅
        String cardNum = formatBcdCardNumber(data, 8, 8);
        if (isValidCardNumber(cardNum)) {
            Log.i(TAG, "Card number found: " + cardNum);
            return cardNum;
        }

        return null;
    }


    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();
        Log.d(TAG, "=== readTransactionHistory ===");

        for (int record = 1; record <= 20; record++) {
            try {
                byte[] cmd = {0x00, (byte) 0xB2, (byte) record, P2_BALANCE_RECORD, LE_RECORD};
                byte[] response = isoDep.transceive(cmd);

                String hexResponse = bytesToHex(response);
                Log.i("TEST2", hexResponse);

                Transaction tx = parseBalanceRecord(response);

                if (tx != null) {
                    transactions.add(tx);
                    Log.i(TAG, String.format("Transaction: %s | %s | %d원 | 잔액: %d원",
                            tx.getDate(), tx.getLocation(), tx.getAmount(), tx.getBalanceAfter()));
                } else if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    if (sw1 == 0x6A) break;
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
     * BALANCE_RECORD (SFI 4) 파싱
     *
     * 정확한 구조:
     * Offset 0:     거래 타입 (0x01=사용, 0x02=충전)
     * Offset 4-5:   잔액 (2 bytes, Big Endian)
     * Offset 12-13: 거래 금액 (2 bytes, Big Endian)
     * Offset 16-19: 거래 유형 코드
     * 끝-2:         Status Word (9000)
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
            // offset 0: 거래 타입 (0x01=사용, 0x02=충전)
            int recordType = data[0] & 0xFF;

            // 잔액 (offset 4-5, Big Endian)
            int balance = get2BytesBE(data, 4);

            // 거래 금액 (offset 12-13, Big Endian)
            int amount = get2BytesBE(data, 12);

            // 거래 타입 판별
            TransactionType txType;
            String location;

            if (recordType == 0x02) {
                // 충전 거래
                txType = TransactionType.CHARGE;
                location = "충전";
            } else {
                // 사용 거래 (0x01)
                txType = TransactionType.USE;
                location = "사용";
            }

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
     * 2바이트 Big Endian 정수 변환
     */
    private int get2BytesBE(byte[] data, int offset) {
        if (offset + 2 > data.length) return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

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

    private String formatBcdCardNumber(byte[] data, int offset, int len) {
        if (offset + len > data.length) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int high = (data[offset + i] >> 4) & 0x0F;
            int low = data[offset + i] & 0x0F;

            // BCD 유효성 검사
            if (high > 9 || low > 9) return null;

            sb.append(high).append(low);
        }

        String raw = sb.toString();
        if (raw.length() >= 16) {
            return raw.substring(0, 4) + " " + raw.substring(4, 8) + " " +
                    raw.substring(8, 12) + " " + raw.substring(12, 16);
        }
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