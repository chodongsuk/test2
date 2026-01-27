package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class TMoneyParser implements CardParser {
    private static final String TAG = "TMoneyParser";

    // BALANCE_TMONEY = { -112, 76, 0, 0, 4 } = 0x90 0x4C 0x00 0x00 0x04
    private static final byte[] CMD_BALANCE = {(byte) 0x90, 0x4C, 0x00, 0x00, 0x04};

    // CARDINFO_TMONEY = { 0, -78, 1, 20, 51 } = 0x00 0xB2 0x01 0x14 0x33 (READ RECORD SFI 2, Record 1, Le=51)
    private static final byte[] CMD_CARDINFO = {0x00, (byte) 0xB2, 0x01, 0x14, 0x33};

    // BALANCE_RECORD_TMONEY = { 0, -78, X, 36, 46 } = SFI 4 (P2=0x24), Le=46
    // TRANS_RECORD_TMONEY = { 0, -78, X, 28, 46 } = SFI 3 (P2=0x1C), Le=46
    private static final byte P2_BALANCE_RECORD = 0x24;  // SFI 4
    private static final byte P2_TRANS_RECORD = 0x1C;    // SFI 3
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

        // 방법 1: CARDINFO_TMONEY 명령 (SFI 2, Record 1)
        try {
            Log.d(TAG, "Trying CARDINFO: " + bytesToHex(CMD_CARDINFO));
            byte[] response = isoDep.transceive(CMD_CARDINFO);
            Log.d(TAG, "CARDINFO response: " + bytesToHex(response));

            String cardNum = processCardInfoResponse(response, isoDep, 0x14);
            if (cardNum != null) return cardNum;
        } catch (Exception e) {
            Log.d(TAG, "CARDINFO failed: " + e.getMessage());
        }

        // 방법 2: GET DATA (90 4A)
        try {
            byte[] cmd = {(byte) 0x90, 0x4A, 0x00, 0x00, 0x00};
            byte[] response = isoDep.transceive(cmd);
            Log.d(TAG, "GET DATA response: " + bytesToHex(response));

            String cardNum = processCardInfoResponse(response, isoDep, -1);
            if (cardNum != null) return cardNum;
        } catch (Exception e) {
            Log.d(TAG, "GET DATA failed: " + e.getMessage());
        }

        Log.w(TAG, "Card number not found");
        return null;
    }

    private String processCardInfoResponse(byte[] response, IsoDep isoDep, int p2ForRetry) throws Exception {
        if (response == null || response.length < 2) return null;

        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;

        if (sw1 == 0x90 && sw2 == 0x00) {
            String cardNum = findCardNumber(response, response.length - 2);
            if (cardNum != null) {
                Log.i(TAG, "Card number found: " + cardNum);
                return cardNum;
            }
        } else if (sw1 == 0x6C && sw2 > 0) {
            // Le 오류 - 올바른 길이로 재시도
            byte[] retryCmd;
            if (p2ForRetry >= 0) {
                retryCmd = new byte[]{0x00, (byte) 0xB2, 0x01, (byte) p2ForRetry, (byte) sw2};
            } else {
                retryCmd = new byte[]{(byte) 0x90, 0x4A, 0x00, 0x00, (byte) sw2};
            }
            response = isoDep.transceive(retryCmd);
            Log.d(TAG, "Retry response: " + bytesToHex(response));

            if (response != null && response.length > 2 && isSuccess(response)) {
                String cardNum = findCardNumber(response, response.length - 2);
                if (cardNum != null) {
                    Log.i(TAG, "Card number found (retry): " + cardNum);
                    return cardNum;
                }
            }
        }
        return null;
    }

    private String findCardNumber(byte[] data, int length) {
        if (length < 8) return null;

        // FCI Template (6F) 응답인 경우: offset 8에서 카드번호 추출
        // 예: 6F31B02F00100108[10191502311833100]...
        if (length >= 16 && (data[0] & 0xFF) == 0x6F) {
            String cardNum = formatBcdCardNumber(data, 8, 8);
            if (isValidCardNumber(cardNum)) {
                Log.d(TAG, "Card number found at FCI offset 8: " + cardNum);
                return cardNum;
            }
        }

        // TLV 태그 검색 (5A, 57)
        for (int i = 0; i < length - 2; i++) {
            int tag = data[i] & 0xFF;

            // Tag 5A (Application PAN)
            if (tag == 0x5A && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (len > 0 && len <= 10 && i + 2 + len <= length) {
                    String cardNum = formatBcdCardNumber(data, i + 2, len);
                    if (isValidCardNumber(cardNum)) return cardNum;
                }
            }

            // Tag 57 (Track 2)
            if (tag == 0x57 && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (len > 0 && len <= 19 && i + 2 + len <= length) {
                    String cardNum = formatTrack2CardNumber(data, i + 2, len);
                    if (isValidCardNumber(cardNum)) return cardNum;
                }
            }
        }

        // BCD 16자리 패턴 검색 (모든 nibble이 0-9인 경우만)
        for (int i = 0; i <= length - 8; i++) {
            if (isValidBcdBlock(data, i, 8)) {
                String cardNum = formatBcdCardNumber(data, i, 8);
                if (isValidCardNumber(cardNum)) return cardNum;
            }
        }

        return null;
    }

    private boolean isValidBcdBlock(byte[] data, int offset, int len) {
        for (int i = 0; i < len && offset + i < data.length; i++) {
            int high = (data[offset + i] >> 4) & 0x0F;
            int low = data[offset + i] & 0x0F;
            if (high > 9 || low > 9) return false;
        }
        return true;
    }

    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();
        Log.d(TAG, "=== readTransactionHistory ===");

        // 방법 1: 90 4E 명령
        for (int i = 1; i <= 10; i++) {
            try {
                byte[] cmd = {(byte) 0x90, 0x4E, 0x00, (byte) i, 0x00};
                byte[] response = isoDep.transceive(cmd);

                Transaction tx = processTransactionResponse(response, isoDep, cmd);
                if (tx != null) {
                    transactions.add(tx);
                } else if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    if (sw1 != 0x90 && sw1 != 0x6C) break;
                }
            } catch (Exception e) {
                break;
            }
        }

        // 방법 2: TRANS_RECORD (SFI 3)
        if (transactions.isEmpty()) {
            for (int record = 1; record <= 10; record++) {
                try {
                    byte[] cmd = {0x00, (byte) 0xB2, (byte) record, P2_TRANS_RECORD, LE_RECORD};
                    byte[] response = isoDep.transceive(cmd);

                    Transaction tx = processTransactionResponse(response, isoDep, cmd);
                    if (tx != null) {
                        transactions.add(tx);
                    } else if (response != null && response.length >= 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        if (sw1 == 0x6A) break;
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }

        // 방법 3: BALANCE_RECORD (SFI 4)
        if (transactions.isEmpty()) {
            for (int record = 1; record <= 10; record++) {
                try {
                    byte[] cmd = {0x00, (byte) 0xB2, (byte) record, P2_BALANCE_RECORD, LE_RECORD};
                    byte[] response = isoDep.transceive(cmd);

                    Transaction tx = processTransactionResponse(response, isoDep, cmd);
                    if (tx != null) {
                        transactions.add(tx);
                    } else if (response != null && response.length >= 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        if (sw1 == 0x6A) break;
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }

        Log.i(TAG, "Found " + transactions.size() + " transactions");
        return transactions;
    }

    private Transaction processTransactionResponse(byte[] response, IsoDep isoDep, byte[] originalCmd) throws Exception {
        if (response == null || response.length < 2) return null;

        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;

        if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
            return parseTransaction(response, response.length - 2);
        } else if (sw1 == 0x6C && sw2 > 0) {
            byte[] retryCmd = originalCmd.clone();
            retryCmd[retryCmd.length - 1] = (byte) sw2;
            response = isoDep.transceive(retryCmd);
            if (response != null && response.length >= 10 && isSuccess(response)) {
                return parseTransaction(response, response.length - 2);
            }
        }
        return null;
    }

    private Transaction parseTransaction(byte[] data, int length) {
        if (length < 8) return null;

        // 빈 레코드 체크
        boolean isEmpty = true;
        for (int i = 0; i < Math.min(8, length); i++) {
            if (data[i] != 0 && (data[i] & 0xFF) != 0xFF) {
                isEmpty = false;
                break;
            }
        }
        if (isEmpty) return null;

        // 포맷 1: [Type 1B][Date 4B][Amount 4B][Balance 4B]
        if (length >= 13) {
            int txType = data[0] & 0xFF;
            String date = parseBcdDate(data, 1);
            int amount = getInt(data, 5);
            int balance = getInt(data, 9);

            if (isValidAmount(amount) && isValidAmount(balance)) {
                return createTransaction(txType, date, amount, balance);
            }

            // 포맷 2: [Date 4B][Type 1B][Amount 4B][Balance 4B]
            date = parseBcdDate(data, 0);
            txType = data[4] & 0xFF;
            amount = getInt(data, 5);
            balance = getInt(data, 9);

            if (isValidAmount(amount) && isValidAmount(balance)) {
                return createTransaction(txType, date, amount, balance);
            }
        }

        return null;
    }

    private Transaction createTransaction(int txType, String date, int amount, int balance) {
        TransactionType type = (txType == 0x04 || txType == 0x05 || txType == 0x10 || txType == 0x11)
                ? TransactionType.CHARGE : TransactionType.USE;
        String desc;
        switch (txType) {
            case 0x01: desc = "승차"; break;
            case 0x02: desc = "하차"; break;
            case 0x03: desc = "환승"; break;
            case 0x04: case 0x05: case 0x10: case 0x11: desc = "충전"; break;
            case 0x20: case 0x21: desc = "결제"; break;
            default: desc = "사용"; break;
        }
        return new Transaction(date, desc, amount, balance, type);
    }

    // ===== 유틸리티 메서드 =====

    private boolean isSuccess(byte[] response) {
        if (response == null || response.length < 2) return false;
        return (response[response.length - 2] & 0xFF) == 0x90 &&
               (response[response.length - 1] & 0xFF) == 0x00;
    }

    private int getInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) |
               ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) |
               (data[offset + 3] & 0xFF);
    }

    private boolean isValidAmount(int amount) {
        return amount > 0 && amount <= 500000;
    }

    private boolean isValidCardNumber(String cardNum) {
        if (cardNum == null) return false;
        String digits = cardNum.replace(" ", "");
        if (digits.length() < 16) return false;
        for (char c : digits.toCharArray()) {
            if (c < '0' || c > '9') return false;
        }
        // 모두 0이면 무효
        for (char c : digits.toCharArray()) {
            if (c != '0') return true;
        }
        return false;
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
        return null;
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
        return null;
    }

    private String parseBcdDate(byte[] data, int offset) {
        if (offset + 4 > data.length) return "";
        int yy = ((data[offset] >> 4) & 0x0F) * 10 + (data[offset] & 0x0F);
        int mm = ((data[offset + 1] >> 4) & 0x0F) * 10 + (data[offset + 1] & 0x0F);
        int dd = ((data[offset + 2] >> 4) & 0x0F) * 10 + (data[offset + 2] & 0x0F);
        int hh = ((data[offset + 3] >> 4) & 0x0F) * 10 + (data[offset + 3] & 0x0F);
        if (mm < 1 || mm > 12 || dd < 1 || dd > 31 || hh > 23) return "";
        return String.format("%02d/%02d/%02d %02d:00", yy, mm, dd, hh);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
