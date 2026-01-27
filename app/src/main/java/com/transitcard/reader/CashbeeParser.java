package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class CashbeeParser implements CardParser {
    private static final String TAG = "CashbeeParser";

    // Command constants from user's specification
    // BALANCE_CASHBEE = { -112, 76, 0, 0, 4 } = 0x90, 0x4C, 0x00, 0x00, 0x04
    private static final byte[] CMD_BALANCE = {(byte) 0x90, 0x4C, 0x00, 0x00, 0x04};

    // CARDINFO_CASHBEE = { 0, -78, 1, 20, 51 } = 0x00, 0xB2, 0x01, 0x14, 0x33 (READ RECORD SFI 2, Record 1, Le=0x33)
    private static final byte[] CMD_CARDINFO = {0x00, (byte) 0xB2, 0x01, 0x14, 0x33};

    // BALANCE_RECORD_CASHBEE = { 0, -78, X, 36, 26 } = 0x00, 0xB2, X, 0x24, 0x1A (SFI 4)
    // TRANS_RECORD_CASHBEE = { 0, -78, X, 28, 26 } = 0x00, 0xB2, X, 0x1C, 0x1A (SFI 3)
    private static final byte P2_BALANCE_RECORD = 0x24;  // SFI 4
    private static final byte P2_TRANS_RECORD = 0x1C;    // SFI 3
    private static final byte LE_RECORD = 0x1A;          // 26 bytes

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // Read balance first
            int balance = readBalance(isoDep);

            // Read card number
            String cardNumber = readCardNumber(isoDep);
            if (cardNumber == null || cardNumber.isEmpty()) {
                cardNumber = bytesToHex(cardId);
            }

            // Read transaction history
            List<Transaction> transactions = readTransactionHistory(isoDep);

            return new TransitCardData(
                    CardType.CASHBEE,
                    cardNumber,
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
            Log.d(TAG, "readBalance: Starting Cashbee balance read...");
            Log.d(TAG, "readBalance: Sending command: " + bytesToHex(CMD_BALANCE));

            byte[] response = isoDep.transceive(CMD_BALANCE);
            Log.d(TAG, "readBalance: Response (" + response.length + " bytes): " + bytesToHex(response));

            if (response.length >= 6) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "readBalance: SW: " + String.format("%02X%02X", sw1, sw2));

                if (sw1 == 0x90 && sw2 == 0x00) {
                    // Balance is stored in first 4 bytes (big-endian)
                    int balance = ((response[0] & 0xFF) << 24) |
                                 ((response[1] & 0xFF) << 16) |
                                 ((response[2] & 0xFF) << 8) |
                                 (response[3] & 0xFF);
                    Log.i(TAG, "readBalance: SUCCESS = " + balance + "원");
                    return balance;
                }
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "readBalance: Error", e);
            return 0;
        }
    }

    /**
     * 카드번호 읽기 - CARDINFO_CASHBEE 명령 사용
     */
    private String readCardNumber(IsoDep isoDep) {
        Log.d(TAG, "=== readCardNumber: Starting card number extraction ===");

        // 방법 1: CARDINFO 명령 (SFI 2, Record 1)
        try {
            Log.d(TAG, "readCardNumber: Sending CARDINFO command: " + bytesToHex(CMD_CARDINFO));
            byte[] response = isoDep.transceive(CMD_CARDINFO);
            Log.d(TAG, "readCardNumber: CARDINFO response: " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    String cardNum = findCardNumberInData(response, response.length - 2);
                    if (cardNum != null) {
                        Log.i(TAG, "readCardNumber: Found from CARDINFO = " + cardNum);
                        return cardNum;
                    }
                } else if (sw1 == 0x6C && sw2 > 0) {
                    // Le 오류 - 올바른 길이로 재시도
                    byte[] retryCmd = {0x00, (byte) 0xB2, 0x01, 0x14, (byte) sw2};
                    response = isoDep.transceive(retryCmd);
                    Log.d(TAG, "readCardNumber: CARDINFO retry response: " + bytesToHex(response));
                    if (response != null && response.length > 2) {
                        String cardNum = findCardNumberInData(response, response.length - 2);
                        if (cardNum != null) {
                            Log.i(TAG, "readCardNumber: Found from CARDINFO retry = " + cardNum);
                            return cardNum;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "readCardNumber: CARDINFO failed - " + e.getMessage());
        }

        // 방법 2: GET DATA 명령 (90 4A)
        try {
            byte[] cmd = {(byte) 0x90, 0x4A, 0x00, 0x00, 0x00};
            byte[] response = isoDep.transceive(cmd);
            Log.d(TAG, "readCardNumber: GET DATA (90 4A) = " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    String cardNum = findCardNumberInData(response, response.length - 2);
                    if (cardNum != null) {
                        Log.i(TAG, "readCardNumber: Found from 90 4A = " + cardNum);
                        return cardNum;
                    }
                } else if (sw1 == 0x6C && sw2 > 0) {
                    byte[] retryCmd = {(byte) 0x90, 0x4A, 0x00, 0x00, (byte) sw2};
                    response = isoDep.transceive(retryCmd);
                    if (response != null && response.length > 2) {
                        String cardNum = findCardNumberInData(response, response.length - 2);
                        if (cardNum != null) {
                            Log.i(TAG, "readCardNumber: Found from 90 4A retry = " + cardNum);
                            return cardNum;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "readCardNumber: GET DATA failed - " + e.getMessage());
        }

        // 방법 3: 여러 SFI Record 읽기
        int[] sfis = {1, 2, 3, 4, 5};
        for (int sfi : sfis) {
            int p2 = (sfi << 3) | 0x04;
            for (int record = 1; record <= 3; record++) {
                try {
                    byte[] cmd = {0x00, (byte) 0xB2, (byte) record, (byte) p2, 0x00};
                    byte[] response = isoDep.transceive(cmd);

                    if (response != null && response.length > 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        int sw2 = response[response.length - 1] & 0xFF;

                        if (sw1 == 0x90 && sw2 == 0x00) {
                            Log.d(TAG, "readCardNumber: SFI" + sfi + " Rec" + record + " = " + bytesToHex(response));
                            String cardNum = findCardNumberInData(response, response.length - 2);
                            if (cardNum != null) {
                                Log.i(TAG, "readCardNumber: Found from SFI" + sfi + " = " + cardNum);
                                return cardNum;
                            }
                        } else if (sw1 == 0x6C && sw2 > 0) {
                            byte[] retryCmd = {0x00, (byte) 0xB2, (byte) record, (byte) p2, (byte) sw2};
                            response = isoDep.transceive(retryCmd);
                            if (response != null && response.length > 2) {
                                String cardNum = findCardNumberInData(response, response.length - 2);
                                if (cardNum != null) {
                                    Log.i(TAG, "readCardNumber: Found from SFI" + sfi + " retry = " + cardNum);
                                    return cardNum;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Continue to next SFI
                }
            }
        }

        Log.w(TAG, "readCardNumber: Card number not found");
        return null;
    }

    /**
     * 거래내역 읽기 - BALANCE_RECORD 및 TRANS_RECORD 명령 사용
     */
    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();
        Log.d(TAG, "=== readTransactionHistory: Starting ===");

        // 방법 1: TRANS_RECORD 읽기 (SFI 3) - 거래 내역
        for (int record = 1; record <= 10; record++) {
            try {
                byte[] cmd = {0x00, (byte) 0xB2, (byte) record, P2_TRANS_RECORD, LE_RECORD};
                byte[] response = isoDep.transceive(cmd);
                Log.d(TAG, "readTransactionHistory: TRANS_RECORD " + record + " = " + bytesToHex(response));

                if (response != null && response.length > 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;

                    if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
                        Transaction tx = parseTransactionRecord(response, response.length - 2);
                        if (tx != null) {
                            transactions.add(tx);
                        }
                    } else if (sw1 == 0x6C && sw2 > 0) {
                        byte[] retryCmd = {0x00, (byte) 0xB2, (byte) record, P2_TRANS_RECORD, (byte) sw2};
                        response = isoDep.transceive(retryCmd);
                        if (response != null && response.length >= 10) {
                            Transaction tx = parseTransactionRecord(response, response.length - 2);
                            if (tx != null) {
                                transactions.add(tx);
                            }
                        }
                    } else if (sw1 == 0x6A) {
                        break; // No more records
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "readTransactionHistory: TRANS_RECORD " + record + " failed - " + e.getMessage());
                break;
            }
        }

        // 방법 2: BALANCE_RECORD 읽기 (SFI 4) - 잔액 변동 내역
        if (transactions.isEmpty()) {
            for (int record = 1; record <= 10; record++) {
                try {
                    byte[] cmd = {0x00, (byte) 0xB2, (byte) record, P2_BALANCE_RECORD, LE_RECORD};
                    byte[] response = isoDep.transceive(cmd);
                    Log.d(TAG, "readTransactionHistory: BALANCE_RECORD " + record + " = " + bytesToHex(response));

                    if (response != null && response.length > 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        int sw2 = response[response.length - 1] & 0xFF;

                        if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
                            Transaction tx = parseTransactionRecord(response, response.length - 2);
                            if (tx != null) {
                                transactions.add(tx);
                            }
                        } else if (sw1 == 0x6C && sw2 > 0) {
                            byte[] retryCmd = {0x00, (byte) 0xB2, (byte) record, P2_BALANCE_RECORD, (byte) sw2};
                            response = isoDep.transceive(retryCmd);
                            if (response != null && response.length >= 10) {
                                Transaction tx = parseTransactionRecord(response, response.length - 2);
                                if (tx != null) {
                                    transactions.add(tx);
                                }
                            }
                        } else if (sw1 == 0x6A) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "readTransactionHistory: BALANCE_RECORD " + record + " failed - " + e.getMessage());
                    break;
                }
            }
        }

        // 방법 3: T-money 스타일 거래내역 (90 4E)
        if (transactions.isEmpty()) {
            for (int i = 1; i <= 10; i++) {
                try {
                    byte[] cmd = {(byte) 0x90, 0x4E, 0x00, (byte) i, 0x00};
                    byte[] response = isoDep.transceive(cmd);
                    Log.d(TAG, "readTransactionHistory: 90 4E record " + i + " = " + bytesToHex(response));

                    if (response != null && response.length > 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        int sw2 = response[response.length - 1] & 0xFF;

                        if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
                            Transaction tx = parseTransactionRecord(response, response.length - 2);
                            if (tx != null) {
                                transactions.add(tx);
                            }
                        } else if (sw1 == 0x6C && sw2 > 0) {
                            byte[] retryCmd = {(byte) 0x90, 0x4E, 0x00, (byte) i, (byte) sw2};
                            response = isoDep.transceive(retryCmd);
                            if (response != null && response.length >= 10) {
                                Transaction tx = parseTransactionRecord(response, response.length - 2);
                                if (tx != null) {
                                    transactions.add(tx);
                                }
                            }
                        } else {
                            break;
                        }
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }

        Log.i(TAG, "readTransactionHistory: Found " + transactions.size() + " transactions");
        return transactions;
    }

    /**
     * 데이터에서 카드번호 패턴 찾기
     */
    private String findCardNumberInData(byte[] data, int length) {
        // TLV 태그 검색 (5A, 57, 9F6B)
        String cardNum = extractCardNumberFromTlv(data, length);
        if (cardNum != null) return cardNum;

        // BCD 인코딩된 16자리 숫자 패턴 찾기
        for (int i = 0; i <= length - 8; i++) {
            if (isValidBcdCardNumber(data, i, 8)) {
                cardNum = formatBcdCardNumber(data, i, 8);
                if (cardNum != null && cardNum.replace(" ", "").length() == 16) {
                    return cardNum;
                }
            }
        }

        // 앞 8바이트가 카드번호일 수 있음
        if (length >= 8) {
            cardNum = formatBcdCardNumber(data, 0, 8);
            if (cardNum != null && isValidCardNumber(cardNum)) {
                return cardNum;
            }
        }

        return null;
    }

    private String extractCardNumberFromTlv(byte[] data, int length) {
        for (int i = 0; i < length - 2; i++) {
            int tag = data[i] & 0xFF;

            // Tag 5A (Application PAN)
            if (tag == 0x5A && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (len > 0 && len <= 10 && i + 2 + len <= length) {
                    String cardNum = formatBcdCardNumber(data, i + 2, len);
                    if (cardNum != null && isValidCardNumber(cardNum)) {
                        return cardNum;
                    }
                }
            }

            // Tag 57 (Track 2 Equivalent Data)
            if (tag == 0x57 && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (len > 0 && len <= 19 && i + 2 + len <= length) {
                    String cardNum = formatTrack2CardNumber(data, i + 2, len);
                    if (cardNum != null && isValidCardNumber(cardNum)) {
                        return cardNum;
                    }
                }
            }

            // Tag 9F6B
            if (tag == 0x9F && i + 1 < length && (data[i + 1] & 0xFF) == 0x6B && i + 2 < length) {
                int len = data[i + 2] & 0xFF;
                if (len > 0 && len <= 19 && i + 3 + len <= length) {
                    String cardNum = formatTrack2CardNumber(data, i + 3, len);
                    if (cardNum != null && isValidCardNumber(cardNum)) {
                        return cardNum;
                    }
                }
            }
        }
        return null;
    }

    private boolean isValidBcdCardNumber(byte[] data, int offset, int len) {
        int digitCount = 0;
        for (int i = 0; i < len && offset + i < data.length; i++) {
            int high = (data[offset + i] >> 4) & 0x0F;
            int low = data[offset + i] & 0x0F;
            if (high <= 9) digitCount++;
            else if (high != 0x0F) return false;
            if (low <= 9) digitCount++;
            else if (low != 0x0F) return false;
        }
        return digitCount >= 16;
    }

    private boolean isValidCardNumber(String cardNum) {
        String digits = cardNum.replace(" ", "");
        if (digits.length() < 16) return false;
        for (char c : digits.toCharArray()) {
            if (c < '0' || c > '9') return false;
        }
        boolean allZero = true;
        for (char c : digits.toCharArray()) {
            if (c != '0') {
                allZero = false;
                break;
            }
        }
        return !allZero;
    }

    private String formatBcdCardNumber(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            int high = (data[offset + i] >> 4) & 0x0F;
            int low = data[offset + i] & 0x0F;
            if (high <= 9) sb.append(high);
            if (low <= 9) sb.append(low);
        }
        String raw = sb.toString();
        if (raw.length() >= 16) {
            return raw.substring(0, 4) + " " + raw.substring(4, 8) + " " +
                   raw.substring(8, 12) + " " + raw.substring(12, 16);
        }
        return raw.length() > 0 ? raw : null;
    }

    private String formatTrack2CardNumber(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            int high = (data[offset + i] >> 4) & 0x0F;
            int low = data[offset + i] & 0x0F;
            if (high == 0x0D || high == 0x0F) break;
            if (high <= 9) sb.append(high);
            if (low == 0x0D || low == 0x0F) break;
            if (low <= 9) sb.append(low);
        }
        String raw = sb.toString();
        if (raw.length() >= 16) {
            return raw.substring(0, 4) + " " + raw.substring(4, 8) + " " +
                   raw.substring(8, 12) + " " + raw.substring(12, 16);
        }
        return raw.length() > 0 ? raw : null;
    }

    /**
     * 거래내역 레코드 파싱
     */
    private Transaction parseTransactionRecord(byte[] data, int length) {
        if (length < 8) return null;

        // 데이터가 모두 0 또는 FF이면 빈 레코드
        boolean isEmpty = true;
        for (int i = 0; i < Math.min(8, length); i++) {
            if (data[i] != 0 && (data[i] & 0xFF) != 0xFF) {
                isEmpty = false;
                break;
            }
        }
        if (isEmpty) return null;

        // 포맷 1: [Type 1B][Date 4B][Amount 4B][Balance 4B]
        Transaction tx = tryParseFormat1(data, length);
        if (tx != null) return tx;

        // 포맷 2: [Date 4B][Type 1B][Amount 4B][Balance 4B]
        tx = tryParseFormat2(data, length);
        if (tx != null) return tx;

        // 포맷 3: 금액만 있는 형식
        tx = tryParseFormat3(data, length);
        return tx;
    }

    private Transaction tryParseFormat1(byte[] data, int length) {
        try {
            if (length < 13) return null;
            int txType = data[0] & 0xFF;
            String date = parseBcdDate(data, 1);
            int amount = ((data[5] & 0xFF) << 24) | ((data[6] & 0xFF) << 16) |
                        ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
            int balanceAfter = ((data[9] & 0xFF) << 24) | ((data[10] & 0xFF) << 16) |
                              ((data[11] & 0xFF) << 8) | (data[12] & 0xFF);
            if (!isValidTransaction(amount, balanceAfter)) return null;
            TransactionType transactionType = getTransactionType(txType);
            String typeDesc = getTransactionTypeDesc(txType);
            return new Transaction(date, typeDesc, amount, balanceAfter, transactionType);
        } catch (Exception e) {
            return null;
        }
    }

    private Transaction tryParseFormat2(byte[] data, int length) {
        try {
            if (length < 13) return null;
            String date = parseBcdDate(data, 0);
            int txType = data[4] & 0xFF;
            int amount = ((data[5] & 0xFF) << 24) | ((data[6] & 0xFF) << 16) |
                        ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
            int balanceAfter = ((data[9] & 0xFF) << 24) | ((data[10] & 0xFF) << 16) |
                              ((data[11] & 0xFF) << 8) | (data[12] & 0xFF);
            if (!isValidTransaction(amount, balanceAfter)) return null;
            TransactionType transactionType = getTransactionType(txType);
            String typeDesc = getTransactionTypeDesc(txType);
            return new Transaction(date, typeDesc, amount, balanceAfter, transactionType);
        } catch (Exception e) {
            return null;
        }
    }

    private Transaction tryParseFormat3(byte[] data, int length) {
        try {
            if (length < 8) return null;
            for (int offset = 0; offset <= length - 8; offset += 4) {
                int amount = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) |
                            ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
                int balanceAfter = ((data[offset + 4] & 0xFF) << 24) | ((data[offset + 5] & 0xFF) << 16) |
                                  ((data[offset + 6] & 0xFF) << 8) | (data[offset + 7] & 0xFF);
                if (isValidTransaction(amount, balanceAfter)) {
                    TransactionType txType = (amount > balanceAfter) ? TransactionType.USE : TransactionType.CHARGE;
                    String typeDesc = (txType == TransactionType.CHARGE) ? "충전" : "사용";
                    return new Transaction("", typeDesc, amount, balanceAfter, txType);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidTransaction(int amount, int balance) {
        if (amount < 0 || balance < 0) return false;
        if (amount > 500000 || balance > 500000) return false;
        if (amount == 0 && balance == 0) return false;
        return true;
    }

    private TransactionType getTransactionType(int txType) {
        switch (txType) {
            case 0x04: case 0x05: case 0x10: case 0x11:
                return TransactionType.CHARGE;
            default:
                return TransactionType.USE;
        }
    }

    private String getTransactionTypeDesc(int txType) {
        switch (txType) {
            case 0x01: return "승차";
            case 0x02: return "하차";
            case 0x03: return "환승";
            case 0x04: case 0x05: case 0x10: case 0x11: return "충전";
            case 0x20: case 0x21: return "결제";
            default: return "사용";
        }
    }

    private String parseBcdDate(byte[] data, int offset) {
        try {
            if (offset + 4 > data.length) return "";
            int b0 = data[offset] & 0xFF;
            int b1 = data[offset + 1] & 0xFF;
            int b2 = data[offset + 2] & 0xFF;
            int b3 = data[offset + 3] & 0xFF;
            int yy = ((b0 >> 4) & 0x0F) * 10 + (b0 & 0x0F);
            int mm = ((b1 >> 4) & 0x0F) * 10 + (b1 & 0x0F);
            int dd = ((b2 >> 4) & 0x0F) * 10 + (b2 & 0x0F);
            int hh = ((b3 >> 4) & 0x0F) * 10 + (b3 & 0x0F);
            if (mm < 1 || mm > 12 || dd < 1 || dd > 31 || hh > 23) return "";
            return String.format("%02d/%02d/%02d %02d:00", yy, mm, dd, hh);
        } catch (Exception e) {
            return "";
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
