package com.transitcard.reader;

import android.nfc.tech.IsoDep;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class TMoneyParser implements CardParser {
    private static final String TAG = "TMoneyParser";

    @Override
    public TransitCardData parse(IsoDep isoDep, byte[] cardId) {
        try {
            // Read card number first
            String cardNumber = readCardNumber(isoDep);
            if (cardNumber == null || cardNumber.isEmpty()) {
                cardNumber = bytesToHex(cardId);
            }

            // Read balance
            int balance = readBalance(isoDep);

            // Read transaction history
            List<Transaction> transactions = readTransactionHistory(isoDep);

            return new TransitCardData(
                    CardType.TMONEY,
                    cardNumber,
                    balance,
                    transactions
            );
        } catch (Exception e) {
            Log.e(TAG, "Error parsing T-money card", e);
            return new TransitCardData(
                    CardType.TMONEY,
                    bytesToHex(cardId),
                    0,
                    new ArrayList<>()
            );
        }
    }

    /**
     * 카드번호 읽기 - SFI 1, Record 1에서 PAN 추출
     */
    private String readCardNumber(IsoDep isoDep) {
        try {
            // READ RECORD: SFI 1, Record 1 (P2 = (SFI << 3) | 0x04 = 0x0C)
            byte[] cmd = {0x00, (byte)0xB2, 0x01, 0x0C, 0x00};
            byte[] response = isoDep.transceive(cmd);
            Log.d(TAG, "readCardNumber: SFI1 Rec1 response = " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    String cardNum = extractCardNumberFromTlv(response, response.length - 2);
                    if (cardNum != null) {
                        Log.i(TAG, "readCardNumber: Found card number = " + cardNum);
                        return cardNum;
                    }
                }
            }

            // 다른 SFI 시도 (SFI 2)
            byte[] cmd2 = {0x00, (byte)0xB2, 0x01, 0x14, 0x00};
            response = isoDep.transceive(cmd2);
            Log.d(TAG, "readCardNumber: SFI2 Rec1 response = " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    String cardNum = extractCardNumberFromTlv(response, response.length - 2);
                    if (cardNum != null) {
                        Log.i(TAG, "readCardNumber: Found card number from SFI2 = " + cardNum);
                        return cardNum;
                    }
                }
            }

            // GET DATA로 카드번호 조회 시도
            byte[] getDataCmd = {(byte)0x90, 0x4A, 0x00, 0x00, 0x10};
            response = isoDep.transceive(getDataCmd);
            Log.d(TAG, "readCardNumber: GET DATA response = " + bytesToHex(response));

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
                    // 응답에서 카드번호 추출 (BCD 인코딩)
                    String cardNum = formatBcdCardNumber(response, 0, 8);
                    if (cardNum != null && cardNum.length() >= 16) {
                        Log.i(TAG, "readCardNumber: Found card number from GET DATA = " + cardNum);
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
     * TLV 데이터에서 카드번호(Tag 5A 또는 57) 추출
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

            // Tag 9F6B (Track 2 Data)
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
     * Track 2 데이터에서 카드번호 추출 (separator 'D' 전까지)
     */
    private String formatTrack2CardNumber(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            int high = (data[offset + i] >> 4) & 0x0F;
            int low = data[offset + i] & 0x0F;
            // 'D'(0x0D) 또는 'F' 패딩에서 중지
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
            Log.d(TAG, "readBalance: Starting...");

            // T-money balance command: 90 4C 00 00 04
            byte[] balanceCommand = new byte[]{
                    (byte) 0x90, (byte) 0x4C, (byte) 0x00, (byte) 0x00,
                    (byte) 0x04
            };
            byte[] response = isoDep.transceive(balanceCommand);
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
     * 거래내역 읽기 - T-money 거래내역 명령 (90 4E)
     */
    private List<Transaction> readTransactionHistory(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();

        try {
            // 1. 먼저 거래내역 개수 조회 (90 4E 00 01 00)
            byte[] countCmd = {(byte)0x90, 0x4E, 0x00, 0x01, 0x00};
            byte[] countResp = isoDep.transceive(countCmd);
            Log.d(TAG, "readTransactionHistory: count response = " + bytesToHex(countResp));

            int recordCount = 10; // 기본값
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
                    Log.d(TAG, "readTransactionHistory: record " + i + " failed - " + e.getMessage());
                }
            }

            // 3. 90 4E 명령이 안되면 READ RECORD로 시도 (SFI 7)
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
     * READ RECORD 명령으로 거래내역 읽기 (대체 방법)
     */
    private List<Transaction> readTransactionHistoryByRecord(IsoDep isoDep) {
        List<Transaction> transactions = new ArrayList<>();

        // SFI 7 (거래내역 저장 위치) - P2 = (7 << 3) | 4 = 0x3C
        int[] sfis = {7, 8, 9}; // 여러 SFI 시도

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
                            // Record not found, stop for this SFI
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
     * 거래내역 레코드 파싱 (90 4E 명령 응답)
     * 일반적인 T-money 거래내역 형식:
     * - Byte 0: 거래 유형 (0x01=승차, 0x02=하차, 0x03=환승, 0x04=충전)
     * - Byte 1-4: 거래일시 (BCD, YYMMDDHHmm)
     * - Byte 5-8: 거래금액 (Big Endian)
     * - Byte 9-12: 거래 후 잔액 (Big Endian)
     * - Byte 13-16: 단말기/노선 정보
     */
    private Transaction parseTransactionRecord(byte[] data) {
        try {
            if (data.length < 14) return null;

            // 거래 유형
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

            // 거래일시 파싱 (BCD)
            String date = parseBcdDate(data, 1);

            // 거래금액 (Big Endian, 4 bytes from offset 5)
            int amount = ((data[5] & 0xFF) << 24) |
                        ((data[6] & 0xFF) << 16) |
                        ((data[7] & 0xFF) << 8) |
                        (data[8] & 0xFF);

            // 거래 후 잔액 (Big Endian, 4 bytes from offset 9)
            int balanceAfter = ((data[9] & 0xFF) << 24) |
                              ((data[10] & 0xFF) << 16) |
                              ((data[11] & 0xFF) << 8) |
                              (data[12] & 0xFF);

            // 금액이 0이거나 너무 크면 무효
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
     * SFI READ RECORD 응답에서 거래내역 파싱
     */
    private Transaction parseTransactionRecordFromSfi(byte[] data) {
        try {
            if (data.length < 14) return null;

            // 데이터가 모두 0이면 빈 레코드
            boolean allZero = true;
            for (int i = 0; i < Math.min(12, data.length - 2); i++) {
                if (data[i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) return null;

            // 거래 유형 추정 (첫 바이트 기반)
            int txType = data[0] & 0xFF;
            TransactionType transactionType = (txType == 0x04 || txType == 0x05) ?
                    TransactionType.CHARGE : TransactionType.USE;
            String typeDesc = (transactionType == TransactionType.CHARGE) ? "충전" : "사용";

            // 거래일시 파싱 시도
            String date = parseBcdDate(data, 1);
            if (date.equals("00/00/00 00:00")) {
                // 다른 오프셋 시도
                date = parseBcdDate(data, 0);
            }

            // 금액과 잔액 파싱 (여러 오프셋 시도)
            int amount = 0;
            int balanceAfter = 0;

            // 오프셋 4-7에서 금액, 8-11에서 잔액 시도
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

            // 값 검증
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
     * @param data 데이터 배열
     * @param offset 시작 오프셋
     * @return "YY/MM/DD HH:MM" 형식
     */
    private String parseBcdDate(byte[] data, int offset) {
        try {
            if (offset + 5 > data.length) return "00/00/00 00:00";

            int yy = ((data[offset] >> 4) & 0x0F) * 10 + (data[offset] & 0x0F);
            int mm = ((data[offset + 1] >> 4) & 0x0F) * 10 + (data[offset + 1] & 0x0F);
            int dd = ((data[offset + 2] >> 4) & 0x0F) * 10 + (data[offset + 2] & 0x0F);
            int hh = ((data[offset + 3] >> 4) & 0x0F) * 10 + (data[offset + 3] & 0x0F);
            int min = ((data[offset + 4] >> 4) & 0x0F) * 10 + (data[offset + 4] & 0x0F);

            // 유효성 검사
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
