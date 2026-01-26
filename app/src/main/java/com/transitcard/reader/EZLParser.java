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
            if (!selectSecondaryAid(isoDep)) {
                Log.w(TAG, "parse: Secondary AID selection failed");
            }

            // Read card number
            String cardNumber = readCardNumber(isoDep);
            if (cardNumber == null || cardNumber.isEmpty()) {
                cardNumber = bytesToHex(cardId);
            }

            // Read balance
            int balance = readBalance(isoDep);

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
     * 카드번호 읽기
     */
    private String readCardNumber(IsoDep isoDep) {
        try {
            // SFI 1, Record 1 읽기
            byte[] cmd = {0x00, (byte)0xB2, 0x01, 0x0C, 0x00};
            byte[] response = isoDep.transceive(cmd);
            Log.d(TAG, "readCardNumber: SFI1 Rec1 = " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    String cardNum = extractCardNumberFromTlv(response, response.length - 2);
                    if (cardNum != null) {
                        Log.i(TAG, "readCardNumber: Found = " + cardNum);
                        return cardNum;
                    }
                }
            }

            // SFI 2 시도
            byte[] cmd2 = {0x00, (byte)0xB2, 0x01, 0x14, 0x00};
            response = isoDep.transceive(cmd2);
            Log.d(TAG, "readCardNumber: SFI2 Rec1 = " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    String cardNum = extractCardNumberFromTlv(response, response.length - 2);
                    if (cardNum != null) {
                        Log.i(TAG, "readCardNumber: Found from SFI2 = " + cardNum);
                        return cardNum;
                    }
                }
            }

            // GET DATA 시도 (90 4A)
            byte[] getDataCmd = {(byte)0x90, 0x4A, 0x00, 0x00, 0x10};
            response = isoDep.transceive(getDataCmd);
            Log.d(TAG, "readCardNumber: GET DATA = " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
                    String cardNum = formatBcdCardNumber(response, 0, 8);
                    if (cardNum != null && cardNum.length() >= 16) {
                        Log.i(TAG, "readCardNumber: Found from GET DATA = " + cardNum);
                        return cardNum;
                    }
                }
            }

        } catch (Exception e) {
            Log.d(TAG, "readCardNumber: Failed - " + e.getMessage());
        }
        return null;
    }

    /**
     * TLV에서 카드번호 추출
     */
    private String extractCardNumberFromTlv(byte[] data, int length) {
        for (int i = 0; i < length - 2; i++) {
            int tag = data[i] & 0xFF;

            // Tag 5A (Application PAN)
            if (tag == 0x5A && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (i + 2 + len <= length && len >= 8) {
                    return formatBcdCardNumber(data, i + 2, len);
                }
            }

            // Tag 57 (Track 2 Equivalent Data)
            if (tag == 0x57 && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (i + 2 + len <= length && len >= 8) {
                    return formatTrack2CardNumber(data, i + 2, len);
                }
            }

            // Tag 9F6B
            if (tag == 0x9F && i + 1 < length && (data[i + 1] & 0xFF) == 0x6B && i + 2 < length) {
                int len = data[i + 2] & 0xFF;
                if (i + 3 + len <= length && len >= 8) {
                    return formatTrack2CardNumber(data, i + 3, len);
                }
            }
        }
        return null;
    }

    /**
     * BCD 인코딩된 카드번호를 "XXXX XXXX XXXX XXXX" 형식으로 변환
     */
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

    /**
     * Track 2 데이터에서 카드번호 추출
     */
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
            // T-money style balance command (90 4C 00 00 04)
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
     * 거래내역 읽기
     */
    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();

        try {
            // 1. 거래내역 개수 조회 (90 4E 00 01 00)
            byte[] countCmd = {(byte)0x90, 0x4E, 0x00, 0x01, 0x00};
            byte[] countResp = isoDep.transceive(countCmd);
            Log.d(TAG, "readTransactionHistory: count = " + bytesToHex(countResp));

            int recordCount = 10;
            if (countResp != null && countResp.length >= 3) {
                int sw1 = countResp[countResp.length - 2] & 0xFF;
                int sw2 = countResp[countResp.length - 1] & 0xFF;
                if (sw1 == 0x90 && sw2 == 0x00) {
                    recordCount = countResp[0] & 0xFF;
                    Log.d(TAG, "readTransactionHistory: recordCount = " + recordCount);
                }
            }

            // 2. 각 거래내역 레코드 읽기 (90 4E 00 02 xx)
            for (int i = 0; i < Math.min(recordCount, 10); i++) {
                try {
                    byte[] readCmd = {(byte)0x90, 0x4E, 0x00, 0x02, (byte)i};
                    byte[] response = isoDep.transceive(readCmd);
                    Log.d(TAG, "readTransactionHistory: record " + i + " = " + bytesToHex(response));

                    if (response != null && response.length >= 16) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        int sw2 = response[response.length - 1] & 0xFF;

                        if (sw1 == 0x90 && sw2 == 0x00) {
                            Transaction tx = parseTransactionRecord(response);
                            if (tx != null) {
                                transactions.add(tx);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "readTransactionHistory: record " + i + " failed");
                }
            }

            // 3. 90 4E가 안되면 READ RECORD로 시도
            if (transactions.isEmpty()) {
                Log.d(TAG, "readTransactionHistory: Trying READ RECORD method...");
                transactions = readTransactionHistoryByRecord(isoDep);
            }

        } catch (Exception e) {
            Log.e(TAG, "readTransactionHistory: Error", e);
        }

        Log.i(TAG, "readTransactionHistory: Found " + transactions.size() + " transactions");
        return transactions;
    }

    /**
     * READ RECORD로 거래내역 읽기 (대체 방법)
     */
    private List<Transaction> readTransactionHistoryByRecord(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();
        int[] sfis = {7, 8, 9};

        for (int sfi : sfis) {
            int p2 = (sfi << 3) | 0x04;
            for (int record = 1; record <= 10; record++) {
                try {
                    byte[] cmd = {0x00, (byte)0xB2, (byte)record, (byte)p2, 0x00};
                    byte[] response = isoDep.transceive(cmd);

                    if (response != null && response.length > 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        int sw2 = response[response.length - 1] & 0xFF;

                        if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 16) {
                            Log.d(TAG, "readTransactionHistoryByRecord: SFI" + sfi + " Rec" + record + " = " + bytesToHex(response));
                            Transaction tx = parseTransactionRecordFromSfi(response);
                            if (tx != null) {
                                transactions.add(tx);
                            }
                        } else if (sw1 == 0x6A && sw2 == 0x83) {
                            break;
                        }
                    }
                } catch (Exception e) {
                    break;
                }
            }
            if (!transactions.isEmpty()) break;
        }

        return transactions;
    }

    /**
     * 거래내역 레코드 파싱
     */
    private Transaction parseTransactionRecord(byte[] data) {
        try {
            if (data.length < 14) return null;

            int txType = data[0] & 0xFF;
            TransactionType transactionType;
            String typeDesc;
            switch (txType) {
                case 0x01:
                    transactionType = TransactionType.USE;
                    typeDesc = "승차";
                    break;
                case 0x02:
                    transactionType = TransactionType.USE;
                    typeDesc = "하차";
                    break;
                case 0x03:
                    transactionType = TransactionType.USE;
                    typeDesc = "환승";
                    break;
                case 0x04:
                case 0x05:
                    transactionType = TransactionType.CHARGE;
                    typeDesc = "충전";
                    break;
                default:
                    transactionType = TransactionType.USE;
                    typeDesc = "사용";
            }

            String date = parseBcdDate(data, 1);

            int amount = ((data[5] & 0xFF) << 24) |
                        ((data[6] & 0xFF) << 16) |
                        ((data[7] & 0xFF) << 8) |
                        (data[8] & 0xFF);

            int balanceAfter = ((data[9] & 0xFF) << 24) |
                              ((data[10] & 0xFF) << 16) |
                              ((data[11] & 0xFF) << 8) |
                              (data[12] & 0xFF);

            if (amount == 0 && balanceAfter == 0) return null;
            if (amount > 1000000 || balanceAfter > 1000000) return null;

            Log.d(TAG, "parseTransactionRecord: type=" + typeDesc + ", date=" + date +
                      ", amount=" + amount + ", balance=" + balanceAfter);

            return new Transaction(date, typeDesc, amount, balanceAfter, transactionType);
        } catch (Exception e) {
            Log.e(TAG, "parseTransactionRecord: Error", e);
            return null;
        }
    }

    /**
     * SFI READ RECORD에서 거래내역 파싱
     */
    private Transaction parseTransactionRecordFromSfi(byte[] data) {
        try {
            if (data.length < 14) return null;

            boolean allZero = true;
            for (int i = 0; i < Math.min(12, data.length - 2); i++) {
                if (data[i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) return null;

            int txType = data[0] & 0xFF;
            TransactionType transactionType = (txType == 0x04 || txType == 0x05) ?
                    TransactionType.CHARGE : TransactionType.USE;
            String typeDesc = (transactionType == TransactionType.CHARGE) ? "충전" : "사용";

            String date = parseBcdDate(data, 1);
            if (date.equals("00/00/00 00:00")) {
                date = parseBcdDate(data, 0);
            }

            int amount = 0;
            int balanceAfter = 0;

            if (data.length >= 12) {
                amount = ((data[4] & 0xFF) << 24) |
                        ((data[5] & 0xFF) << 16) |
                        ((data[6] & 0xFF) << 8) |
                        (data[7] & 0xFF);
                balanceAfter = ((data[8] & 0xFF) << 24) |
                              ((data[9] & 0xFF) << 16) |
                              ((data[10] & 0xFF) << 8) |
                              (data[11] & 0xFF);
            }

            if (amount > 500000) amount = 0;
            if (balanceAfter > 500000) balanceAfter = 0;

            if (amount == 0 && balanceAfter == 0) return null;

            return new Transaction(date, typeDesc, amount, balanceAfter, transactionType);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * BCD 인코딩된 날짜 파싱
     */
    private String parseBcdDate(byte[] data, int offset) {
        try {
            if (offset + 5 > data.length) return "00/00/00 00:00";

            int yy = ((data[offset] >> 4) & 0x0F) * 10 + (data[offset] & 0x0F);
            int mm = ((data[offset + 1] >> 4) & 0x0F) * 10 + (data[offset + 1] & 0x0F);
            int dd = ((data[offset + 2] >> 4) & 0x0F) * 10 + (data[offset + 2] & 0x0F);
            int hh = ((data[offset + 3] >> 4) & 0x0F) * 10 + (data[offset + 3] & 0x0F);
            int min = ((data[offset + 4] >> 4) & 0x0F) * 10 + (data[offset + 4] & 0x0F);

            if (mm < 1 || mm > 12 || dd < 1 || dd > 31 || hh > 23 || min > 59) {
                return "00/00/00 00:00";
            }

            return String.format("%02d/%02d/%02d %02d:%02d", yy, mm, dd, hh, min);
        } catch (Exception e) {
            return "00/00/00 00:00";
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
