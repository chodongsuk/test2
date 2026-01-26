package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class EZLParser implements CardParser {
    private static final String TAG = "EZLParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        Log.d(TAG, "=== EZLParser.parse: Starting EZL card parsing ===");
        try {
            // Select Secondary AID first (required for EZL)
            selectSecondaryAid(isoDep);

            // Read balance first
            int balance = readBalance(isoDep);

            // Read card number
            String cardNumber = readCardNumber(isoDep);
            if (cardNumber == null || cardNumber.isEmpty()) {
                cardNumber = bytesToHex(cardId);
            }

            // Read transaction history
            List<Transaction> transactions = readTransactionHistory(isoDep);

            Log.i(TAG, "parse: SUCCESS - balance=" + balance + ", cardNumber=" + cardNumber);
            return new TransitCardData(
                    CardType.EZL,
                    cardNumber,
                    balance,
                    transactions
            );
        } catch (Exception e) {
            Log.e(TAG, "parse: Error parsing EZL card", e);
            return new TransitCardData(
                    CardType.EZL,
                    bytesToHex(cardId),
                    0,
                    new ArrayList<>()
            );
        }
    }

    /**
     * Secondary AID 선택 (D4100000140001)
     */
    private boolean selectSecondaryAid(IsoDep isoDep) {
        try {
            byte[] selectCmd = new byte[]{
                    0x00, (byte)0xA4, 0x04, 0x00, 0x07,
                    (byte)0xD4, 0x10, 0x00, 0x00, 0x14, 0x00, 0x01,
                    0x00
            };
            byte[] response = isoDep.transceive(selectCmd);
            Log.d(TAG, "selectSecondaryAid: response = " + bytesToHex(response));

            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                return sw1 == 0x90 && sw2 == 0x00;
            }
        } catch (Exception e) {
            Log.d(TAG, "selectSecondaryAid: Failed - " + e.getMessage());
        }
        return false;
    }

    /**
     * 카드번호 읽기 - 여러 방법 시도
     */
    private String readCardNumber(IsoDep isoDep) {
        Log.d(TAG, "=== readCardNumber: Starting ===");

        // 방법 1: GET DATA 명령 (90 4A)
        try {
            byte[] cmd = {(byte)0x90, 0x4A, 0x00, 0x00, 0x00};
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
                } else if (sw1 == 0x6C) {
                    byte[] retryCmd = {(byte)0x90, 0x4A, 0x00, 0x00, (byte)sw2};
                    response = isoDep.transceive(retryCmd);
                    Log.d(TAG, "readCardNumber: GET DATA retry = " + bytesToHex(response));
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
            Log.d(TAG, "readCardNumber: 90 4A failed - " + e.getMessage());
        }

        // 방법 2: 여러 SFI의 Record 읽기
        int[] sfis = {1, 2, 3, 4, 5, 6};
        for (int sfi : sfis) {
            int p2 = (sfi << 3) | 0x04;
            for (int record = 1; record <= 3; record++) {
                try {
                    byte[] cmd = {0x00, (byte)0xB2, (byte)record, (byte)p2, 0x00};
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
                        } else if (sw1 == 0x6C) {
                            byte[] retryCmd = {0x00, (byte)0xB2, (byte)record, (byte)p2, (byte)sw2};
                            response = isoDep.transceive(retryCmd);
                            if (response != null && response.length > 2) {
                                Log.d(TAG, "readCardNumber: SFI" + sfi + " Rec" + record + " retry = " + bytesToHex(response));
                                String cardNum = findCardNumberInData(response, response.length - 2);
                                if (cardNum != null) {
                                    Log.i(TAG, "readCardNumber: Found from SFI" + sfi + " retry = " + cardNum);
                                    return cardNum;
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // 다음 SFI 시도
                }
            }
        }

        // 방법 3: READ BINARY
        try {
            byte[] cmd = {0x00, (byte)0xB0, 0x00, 0x00, 0x20};
            byte[] response = isoDep.transceive(cmd);
            Log.d(TAG, "readCardNumber: READ BINARY = " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    String cardNum = findCardNumberInData(response, response.length - 2);
                    if (cardNum != null) {
                        Log.i(TAG, "readCardNumber: Found from READ BINARY = " + cardNum);
                        return cardNum;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "readCardNumber: READ BINARY failed - " + e.getMessage());
        }

        Log.w(TAG, "readCardNumber: Card number not found");
        return null;
    }

    /**
     * 데이터에서 카드번호 패턴 찾기
     */
    private String findCardNumberInData(byte[] data, int length) {
        String cardNum = extractCardNumberFromTlv(data, length);
        if (cardNum != null) return cardNum;

        for (int i = 0; i <= length - 8; i++) {
            if (isValidBcdCardNumber(data, i, 8)) {
                cardNum = formatBcdCardNumber(data, i, 8);
                if (cardNum != null && cardNum.replace(" ", "").length() == 16) {
                    return cardNum;
                }
            }
        }

        if (length >= 8) {
            cardNum = formatBcdCardNumber(data, 0, 8);
            if (cardNum != null && isValidCardNumber(cardNum)) {
                return cardNum;
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

    private String extractCardNumberFromTlv(byte[] data, int length) {
        for (int i = 0; i < length - 2; i++) {
            int tag = data[i] & 0xFF;

            if (tag == 0x5A && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (len > 0 && len <= 10 && i + 2 + len <= length) {
                    String cardNum = formatBcdCardNumber(data, i + 2, len);
                    if (cardNum != null && isValidCardNumber(cardNum)) {
                        return cardNum;
                    }
                }
            }

            if (tag == 0x57 && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (len > 0 && len <= 19 && i + 2 + len <= length) {
                    String cardNum = formatTrack2CardNumber(data, i + 2, len);
                    if (cardNum != null && isValidCardNumber(cardNum)) {
                        return cardNum;
                    }
                }
            }

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

    private int readBalance(IsoDep isoDep) {
        try {
            byte[] balanceCmd = new byte[]{
                    (byte) 0x90, (byte) 0x4C, (byte) 0x00, (byte) 0x00, (byte) 0x04
            };
            byte[] response = isoDep.transceive(balanceCmd);
            Log.d(TAG, "readBalance: response = " + bytesToHex(response));

            if (response.length >= 6) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
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
     * 거래내역 읽기 - 여러 방법 시도
     */
    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();
        Log.d(TAG, "=== readTransactionHistory: Starting ===");

        // 방법 1: 90 4E 명령
        try {
            for (int i = 1; i <= 10; i++) {
                byte[] cmd = {(byte)0x90, 0x4E, 0x00, (byte)i, 0x00};
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
                        byte[] retryCmd = {(byte)0x90, 0x4E, 0x00, (byte)i, (byte)sw2};
                        response = isoDep.transceive(retryCmd);
                        Log.d(TAG, "readTransactionHistory: 90 4E record " + i + " retry = " + bytesToHex(response));
                        if (response != null && response.length > 2) {
                            Transaction tx = parseTransactionRecord(response, response.length - 2);
                            if (tx != null) {
                                transactions.add(tx);
                            }
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "readTransactionHistory: 90 4E failed - " + e.getMessage());
        }

        // 방법 2: SFI 기반 READ RECORD
        if (transactions.isEmpty()) {
            Log.d(TAG, "readTransactionHistory: Trying SFI method...");
            int[] sfis = {7, 8, 9, 10, 11, 12};

            for (int sfi : sfis) {
                int p2 = (sfi << 3) | 0x04;
                for (int record = 1; record <= 10; record++) {
                    try {
                        byte[] cmd = {0x00, (byte)0xB2, (byte)record, (byte)p2, 0x00};
                        byte[] response = isoDep.transceive(cmd);

                        if (response != null && response.length > 2) {
                            int sw1 = response[response.length - 2] & 0xFF;
                            int sw2 = response[response.length - 1] & 0xFF;

                            if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
                                Log.d(TAG, "readTransactionHistory: SFI" + sfi + " Rec" + record + " = " + bytesToHex(response));
                                Transaction tx = parseTransactionRecord(response, response.length - 2);
                                if (tx != null) {
                                    transactions.add(tx);
                                }
                            } else if (sw1 == 0x6C && sw2 > 0) {
                                byte[] retryCmd = {0x00, (byte)0xB2, (byte)record, (byte)p2, (byte)sw2};
                                response = isoDep.transceive(retryCmd);
                                if (response != null && response.length > 2) {
                                    Log.d(TAG, "readTransactionHistory: SFI" + sfi + " Rec" + record + " retry = " + bytesToHex(response));
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
                        break;
                    }
                }
                if (!transactions.isEmpty()) {
                    Log.i(TAG, "readTransactionHistory: Found transactions in SFI " + sfi);
                    break;
                }
            }
        }

        // 방법 3: GET RECORD (90 B2)
        if (transactions.isEmpty()) {
            Log.d(TAG, "readTransactionHistory: Trying GET RECORD method...");
            try {
                for (int i = 0; i < 10; i++) {
                    byte[] cmd = {(byte)0x90, (byte)0xB2, (byte)i, 0x00, 0x00};
                    byte[] response = isoDep.transceive(cmd);

                    if (response != null && response.length > 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        int sw2 = response[response.length - 1] & 0xFF;

                        if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
                            Log.d(TAG, "readTransactionHistory: 90 B2 record " + i + " = " + bytesToHex(response));
                            Transaction tx = parseTransactionRecord(response, response.length - 2);
                            if (tx != null) {
                                transactions.add(tx);
                            }
                        } else if (sw1 == 0x6C && sw2 > 0) {
                            byte[] retryCmd = {(byte)0x90, (byte)0xB2, (byte)i, 0x00, (byte)sw2};
                            response = isoDep.transceive(retryCmd);
                            if (response != null && response.length > 2) {
                                Log.d(TAG, "readTransactionHistory: 90 B2 record " + i + " retry = " + bytesToHex(response));
                                Transaction tx = parseTransactionRecord(response, response.length - 2);
                                if (tx != null) {
                                    transactions.add(tx);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "readTransactionHistory: 90 B2 failed - " + e.getMessage());
            }
        }

        Log.i(TAG, "readTransactionHistory: Found " + transactions.size() + " transactions");
        return transactions;
    }

    /**
     * 거래내역 레코드 파싱 - 여러 포맷 시도
     */
    private Transaction parseTransactionRecord(byte[] data, int length) {
        if (length < 8) return null;

        boolean isEmpty = true;
        for (int i = 0; i < Math.min(8, length); i++) {
            if (data[i] != 0 && (data[i] & 0xFF) != 0xFF) {
                isEmpty = false;
                break;
            }
        }
        if (isEmpty) return null;

        Transaction tx = tryParseFormat1(data, length);
        if (tx != null) return tx;

        tx = tryParseFormat2(data, length);
        if (tx != null) return tx;

        tx = tryParseFormat3(data, length);
        if (tx != null) return tx;

        return null;
    }

    private Transaction tryParseFormat1(byte[] data, int length) {
        try {
            if (length < 13) return null;

            int txType = data[0] & 0xFF;
            String date = parseBcdDate(data, 1);

            int amount = ((data[5] & 0xFF) << 24) |
                        ((data[6] & 0xFF) << 16) |
                        ((data[7] & 0xFF) << 8) |
                        (data[8] & 0xFF);

            int balanceAfter = ((data[9] & 0xFF) << 24) |
                              ((data[10] & 0xFF) << 16) |
                              ((data[11] & 0xFF) << 8) |
                              (data[12] & 0xFF);

            if (!isValidTransaction(amount, balanceAfter)) return null;

            TransactionType transactionType = getTransactionType(txType);
            String typeDesc = getTransactionTypeDesc(txType);

            Log.d(TAG, "parseFormat1: type=" + typeDesc + ", date=" + date +
                      ", amount=" + amount + ", balance=" + balanceAfter);

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

            int amount = ((data[5] & 0xFF) << 24) |
                        ((data[6] & 0xFF) << 16) |
                        ((data[7] & 0xFF) << 8) |
                        (data[8] & 0xFF);

            int balanceAfter = ((data[9] & 0xFF) << 24) |
                              ((data[10] & 0xFF) << 16) |
                              ((data[11] & 0xFF) << 8) |
                              (data[12] & 0xFF);

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
                int amount = ((data[offset] & 0xFF) << 24) |
                            ((data[offset + 1] & 0xFF) << 16) |
                            ((data[offset + 2] & 0xFF) << 8) |
                            (data[offset + 3] & 0xFF);

                int balanceAfter = ((data[offset + 4] & 0xFF) << 24) |
                                  ((data[offset + 5] & 0xFF) << 16) |
                                  ((data[offset + 6] & 0xFF) << 8) |
                                  (data[offset + 7] & 0xFF);

                if (isValidTransaction(amount, balanceAfter)) {
                    TransactionType txType = (amount > balanceAfter) ?
                            TransactionType.USE : TransactionType.CHARGE;
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
            case 0x04:
            case 0x05:
            case 0x10:
            case 0x11:
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
            case 0x04:
            case 0x05:
            case 0x10:
            case 0x11:
                return "충전";
            case 0x20:
            case 0x21:
                return "결제";
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

            if (mm < 1 || mm > 12 || dd < 1 || dd > 31 || hh > 23) {
                return "";
            }

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
