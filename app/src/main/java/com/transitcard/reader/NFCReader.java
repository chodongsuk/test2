package com.transitcard.reader;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NfcA;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class NFCReader {
    private static final String TAG = "NFCReader";

    public TransitCardData readCard(Tag tag) {
        Log.d(TAG, "=== readCard: Starting card read ===");
        try {
            byte[] id = tag.getId();
            Log.d(TAG, "readCard: Card ID = " + bytesToHex(id));
            Log.d(TAG, "readCard: Card ID length = " + id.length + " bytes");

            // Log all available technologies
            String[] techList = tag.getTechList();
            Log.d(TAG, "readCard: Available technologies:");
            for (String tech : techList) {
                Log.d(TAG, "readCard:   - " + tech);
            }

            // Try MifareClassic first (EZL cards use this)
            MifareClassic mifareClassic = MifareClassic.get(tag);
            Log.d(TAG, "readCard: MifareClassic available = " + (mifareClassic != null));
            TransitCardData mifareResult = null;
            if (mifareClassic != null) {
                mifareResult = readMifareClassicCard(mifareClassic, id);
                Log.d(TAG, "readCard: MifareClassic result balance = " + (mifareResult != null ? mifareResult.getBalance() : "null"));
            }

            // Try IsoDep (most common for Korean transit cards)
            IsoDep isoDep = IsoDep.get(tag);
            Log.d(TAG, "readCard: IsoDep available = " + (isoDep != null));
            TransitCardData isoDepResult = null;
            if (isoDep != null) {
                isoDepResult = readIsoDepCard(isoDep, id);
                Log.d(TAG, "readCard: IsoDep result balance = " + (isoDepResult != null ? isoDepResult.getBalance() : "null"));
            }

            // 둘 중 잔액이 있는 결과 반환
            if (mifareResult != null && mifareResult.getBalance() > 0) {
                Log.i(TAG, "readCard: Using MifareClassic result with balance " + mifareResult.getBalance());
                return mifareResult;
            }
            if (isoDepResult != null && isoDepResult.getBalance() > 0) {
                Log.i(TAG, "readCard: Using IsoDep result with balance " + isoDepResult.getBalance());
                return isoDepResult;
            }

            // 잔액이 없어도 결과가 있으면 반환
            if (mifareResult != null) {
                return mifareResult;
            }
            if (isoDepResult != null) {
                return isoDepResult;
            }

            // Try NfcA
            NfcA nfcA = NfcA.get(tag);
            Log.d(TAG, "readCard: NfcA available = " + (nfcA != null));
            if (nfcA != null) {
                return readNfcACard(nfcA, id);
            }

            Log.w(TAG, "readCard: No supported NFC technology found");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "readCard: Error reading card", e);
            return null;
        }
    }

    private TransitCardData readIsoDepCard(IsoDep isoDep, byte[] cardId) {
        Log.d(TAG, "readIsoDepCard: Starting IsoDep card read");
        try {
            isoDep.connect();
            isoDep.setTimeout(5000);
            Log.d(TAG, "readIsoDepCard: Connected to card, timeout=5000ms");
            Log.d(TAG, "readIsoDepCard: Max transceive length = " + isoDep.getMaxTransceiveLength());

            // Detect card type based on card ID and AID
            CardType cardType = detectCardType(cardId, isoDep);
            Log.i(TAG, "readIsoDepCard: Detected card type = " + cardType);

            // Check if KFTC balance was found during detection
            if (kftcBalance > 0) {
                // 카드번호가 있으면 사용, 없으면 UID 사용
                String cardNumber = (kftcCardNumber != null) ? kftcCardNumber : bytesToHex(cardId);
                Log.i(TAG, "readIsoDepCard: Using KFTC balance = " + kftcBalance + "원, cardNumber = " + cardNumber);
                isoDep.close();
                return new TransitCardData(
                        CardType.EZL,
                        cardNumber,
                        kftcBalance
                );
            }

            CardParser parser = null;
            switch (cardType) {
                case TMONEY:
                    parser = new TMoneyParser();
                    break;
                case CASHBEE:
                    parser = new CashbeeParser();
                    break;
                case HANPAY:
                    parser = new HanpayParser();
                    break;
                case RAILPLUS:
                    parser = new RailplusParser();
                    break;
                case MPASS:
                    parser = new MPassParser();
                    break;
                case SEOUL_CITY_PASS:
                    parser = new SeoulCityPassParser();
                    break;
                case KOREA_TOUR_CARD:
                    parser = new KoreaTourCardParser();
                    break;
                case EZL:
                    parser = new EZLParser();
                    break;
            }

            TransitCardData result = null;
            if (parser != null) {
                Log.d(TAG, "readIsoDepCard: Using parser = " + parser.getClass().getSimpleName());
                result = parser.parse(isoDep, cardId);
                Log.d(TAG, "readIsoDepCard: Parse result = " + (result != null ? "success" : "null"));
            } else {
                Log.w(TAG, "readIsoDepCard: No parser available for card type " + cardType);
            }

            isoDep.close();
            Log.d(TAG, "readIsoDepCard: Connection closed");
            return result;
        } catch (Exception e) {
            Log.e(TAG, "readIsoDepCard: Error reading IsoDep card", e);
            try {
                isoDep.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private TransitCardData readNfcACard(NfcA nfcA, byte[] cardId) {
        try {
            nfcA.connect();

            // Create basic card data
            CardType cardType = detectCardTypeFromId(cardId);
            String cardNumber = bytesToHex(cardId);

            nfcA.close();

            return new TransitCardData(cardType, cardNumber, 0);
        } catch (Exception e) {
            Log.e(TAG, "Error reading NfcA card", e);
            try {
                nfcA.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private TransitCardData readMifareClassicCard(MifareClassic mifareClassic, byte[] cardId) {
        Log.d(TAG, "=== readMifareClassicCard: Starting Mifare Classic read ===");
        try {
            mifareClassic.connect();
            Log.d(TAG, "readMifareClassicCard: Connected to card");
            Log.d(TAG, "readMifareClassicCard: Card type = " + mifareClassic.getType());
            Log.d(TAG, "readMifareClassicCard: Sector count = " + mifareClassic.getSectorCount());
            Log.d(TAG, "readMifareClassicCard: Block count = " + mifareClassic.getBlockCount());
            Log.d(TAG, "readMifareClassicCard: Size = " + mifareClassic.getSize() + " bytes");

            int balance = 0;

            // 다양한 키 시도
            byte[] KEY_DEFAULT = MifareClassic.KEY_DEFAULT; // FF FF FF FF FF FF
            byte[] KEY_MIFARE_APPLICATION_DIRECTORY = MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY;
            byte[] KEY_NFC_FORUM = MifareClassic.KEY_NFC_FORUM;
            byte[] KEY_KOREAN_TRANSIT = new byte[]{(byte)0xA0, (byte)0xA1, (byte)0xA2, (byte)0xA3, (byte)0xA4, (byte)0xA5};

            byte[][] keysToTry = {KEY_DEFAULT, KEY_MIFARE_APPLICATION_DIRECTORY, KEY_NFC_FORUM, KEY_KOREAN_TRANSIT};
            String[] keyNames = {"DEFAULT", "MAD", "NFC_FORUM", "KOREAN_TRANSIT"};

            // 모든 섹터 스캔하여 데이터가 있는 곳 찾기
            Log.d(TAG, "readMifareClassicCard: === Scanning ALL sectors for data ===");

            for (int sector = 0; sector < mifareClassic.getSectorCount(); sector++) {
                boolean authenticated = false;
                String usedKey = "";

                for (int k = 0; k < keysToTry.length && !authenticated; k++) {
                    try {
                        if (mifareClassic.authenticateSectorWithKeyA(sector, keysToTry[k])) {
                            authenticated = true;
                            usedKey = keyNames[k] + "(A)";
                        }
                    } catch (Exception e) {
                        Log.d(TAG, "readMifareClassicCard: Sector " + sector + " KeyA " + keyNames[k] + " failed: " + e.getMessage());
                    }

                    if (!authenticated) {
                        try {
                            if (mifareClassic.authenticateSectorWithKeyB(sector, keysToTry[k])) {
                                authenticated = true;
                                usedKey = keyNames[k] + "(B)";
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "readMifareClassicCard: Sector " + sector + " KeyB " + keyNames[k] + " failed: " + e.getMessage());
                        }
                    }
                }

                if (authenticated) {
                    Log.i(TAG, "readMifareClassicCard: Sector " + sector + " authenticated with " + usedKey);
                    int firstBlock = mifareClassic.sectorToBlock(sector);
                    int blockCount = mifareClassic.getBlockCountInSector(sector);

                    for (int i = 0; i < blockCount - 1; i++) { // 마지막 블록은 트레일러
                        int blockIndex = firstBlock + i;
                        try {
                            byte[] blockData = mifareClassic.readBlock(blockIndex);
                            String hexData = bytesToHex(blockData);

                            // 모든 블록 출력 (빈 블록도 포함)
                            Log.d(TAG, "readMifareClassicCard: [Sector " + sector + "] Block " + blockIndex + " = " + hexData);

                            // 데이터가 있는 블록
                            if (!hexData.equals("00000000000000000000000000000000")) {
                                Log.i(TAG, "readMifareClassicCard: [Sector " + sector + "] Block " + blockIndex + " (" + usedKey + ") = " + hexData + " <-- DATA FOUND!");

                                // 잔액 파싱 시도 - 다양한 위치와 형식 시도
                                tryParseBalance(blockData, sector, blockIndex);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "readMifareClassicCard: Error reading sector " + sector + " block " + blockIndex + ": " + e.getMessage());
                        }
                    }

                    // 트레일러 블록도 읽어보기 (키 정보)
                    try {
                        int trailerBlock = firstBlock + blockCount - 1;
                        byte[] trailerData = mifareClassic.readBlock(trailerBlock);
                        Log.d(TAG, "readMifareClassicCard: [Sector " + sector + "] Trailer Block " + trailerBlock + " = " + bytesToHex(trailerData));
                    } catch (Exception e) {
                        Log.d(TAG, "readMifareClassicCard: Could not read trailer block for sector " + sector);
                    }
                } else {
                    Log.w(TAG, "readMifareClassicCard: Sector " + sector + " - AUTHENTICATION FAILED with all keys");
                }
            }

            // 잔액을 찾지 못한 경우, 알려진 EZL 잔액 위치 시도
            // EZL 카드의 잔액은 보통 특정 블록에 저장됨
            Log.d(TAG, "readMifareClassicCard: === Trying known EZL balance locations ===");

            // 잔액이 저장될 수 있는 일반적인 블록들 시도
            int[] possibleBalanceBlocks = {1, 2, 4, 5, 6, 8, 9, 12, 13, 14, 16, 17, 18};

            for (int blockIndex : possibleBalanceBlocks) {
                int sector = mifareClassic.blockToSector(blockIndex);
                try {
                    if (mifareClassic.authenticateSectorWithKeyA(sector, KEY_DEFAULT)) {
                        byte[] blockData = mifareClassic.readBlock(blockIndex);
                        String hexData = bytesToHex(blockData);

                        if (!hexData.equals("00000000000000000000000000000000")) {
                            // 다양한 오프셋과 엔디안으로 잔액 파싱 시도
                            for (int offset = 0; offset <= 12; offset += 2) {
                                if (offset + 4 <= blockData.length) {
                                    int balanceLE = ByteBuffer.wrap(blockData, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                                    int balanceBE = ByteBuffer.wrap(blockData, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();

                                    // 합리적인 잔액 범위인지 확인 (0 ~ 500,000원)
                                    if (balanceLE > 0 && balanceLE < 500000) {
                                        Log.i(TAG, "readMifareClassicCard: Possible balance at block " + blockIndex + " offset " + offset + " (LE): " + balanceLE + "원");
                                        if (balance == 0) balance = balanceLE;
                                    }
                                    if (balanceBE > 0 && balanceBE < 500000) {
                                        Log.i(TAG, "readMifareClassicCard: Possible balance at block " + blockIndex + " offset " + offset + " (BE): " + balanceBE + "원");
                                        if (balance == 0) balance = balanceBE;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            mifareClassic.close();
            Log.d(TAG, "readMifareClassicCard: Connection closed, final balance = " + balance);

            return new TransitCardData(
                    CardType.EZL,
                    bytesToHex(cardId),
                    balance
            );
        } catch (Exception e) {
            Log.e(TAG, "readMifareClassicCard: Error reading Mifare Classic card", e);
            try {
                mifareClassic.close();
            } catch (Exception ignored) {}
            return null;
        }
    }

    private void tryParseBalance(byte[] blockData, int sector, int blockIndex) {
        // 다양한 오프셋과 엔디안으로 잔액 파싱 시도
        for (int offset = 0; offset <= 12; offset += 2) {
            if (offset + 4 <= blockData.length) {
                int balanceLE = ByteBuffer.wrap(blockData, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                int balanceBE = ByteBuffer.wrap(blockData, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();

                // 합리적인 잔액 범위인지 확인 (100 ~ 500,000원)
                if (balanceLE >= 100 && balanceLE < 500000) {
                    Log.i(TAG, "  -> Possible balance at offset " + offset + " (LE): " + balanceLE + "원");
                }
                if (balanceBE >= 100 && balanceBE < 500000) {
                    Log.i(TAG, "  -> Possible balance at offset " + offset + " (BE): " + balanceBE + "원");
                }

                // 2바이트 값도 확인 (잔액이 작은 경우)
                if (offset + 2 <= blockData.length) {
                    int balance2LE = ByteBuffer.wrap(blockData, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                    int balance2BE = ByteBuffer.wrap(blockData, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;

                    if (balance2LE >= 100 && balance2LE < 100000) {
                        Log.i(TAG, "  -> Possible balance (2byte) at offset " + offset + " (LE): " + balance2LE + "원");
                    }
                    if (balance2BE >= 100 && balance2BE < 100000) {
                        Log.i(TAG, "  -> Possible balance (2byte) at offset " + offset + " (BE): " + balance2BE + "원");
                    }
                }
            }
        }
    }

    private CardType detectCardType(byte[] cardId, IsoDep isoDep) {
        Log.d(TAG, "detectCardType: Starting card type detection");
        Log.d(TAG, "detectCardType: Card ID = " + bytesToHex(cardId));

        // 다양한 한국 교통카드 AID 목록
        byte[][] aidList = {
                // T-money 계열
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x01},
                // Cashbee
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x02},
                // Rail+
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x03},
                // 한페이/원패스
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x04},
                // EZL 추정 AID
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x05},
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x06},
                // 추가 한국 교통카드 AID
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x00, (byte) 0x00},
                {(byte) 0xD4, (byte) 0x10, (byte) 0x00, (byte) 0x00, (byte) 0x30, (byte) 0x00, (byte) 0x01},
                // KFTC (금융결제원) AID
                {(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x04, (byte) 0x52, (byte) 0x00, (byte) 0x01},
                // Visa Contactless
                {(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x10, (byte) 0x10},
                // Mastercard Contactless
                {(byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x04, (byte) 0x10, (byte) 0x10},
        };

        String[] aidNames = {
                "T-money", "Cashbee", "Rail+", "Hanpay", "EZL-1", "EZL-2",
                "Korea-Transit-0", "Korea-Transit-Alt", "KFTC", "Visa", "Mastercard"
        };

        // 모든 AID 시도
        for (int i = 0; i < aidList.length; i++) {
            Log.d(TAG, "detectCardType: Trying " + aidNames[i] + " AID...");
            try {
                byte[] response = selectAID(isoDep, aidList[i]);
                if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;
                    Log.d(TAG, "detectCardType: " + aidNames[i] + " response SW=" + String.format("%02X%02X", sw1, sw2));
                    if (sw1 == 0x90 && sw2 == 0x00) {
                        Log.i(TAG, "detectCardType: Card detected with AID: " + aidNames[i]);
                        // AID에 따라 카드 타입 반환
                        switch (i) {
                            case 0: return CardType.TMONEY;
                            case 1: return CardType.CASHBEE;
                            case 2: return CardType.RAILPLUS;
                            case 3: return CardType.HANPAY;
                            case 4:
                            case 5: return CardType.EZL;
                            case 8: // KFTC
                                Log.i(TAG, "detectCardType: KFTC AID succeeded, trying KFTC commands");
                                kftcBalance = 0; // Reset before trying
                                tryKftcCommands(isoDep);
                                Log.i(TAG, "detectCardType: KFTC balance after commands = " + kftcBalance);
                                return CardType.EZL;
                            default:
                                // 알 수 없는 AID지만 성공하면 EZL로 시도
                                Log.i(TAG, "detectCardType: Unknown AID succeeded, trying as EZL");
                                return CardType.EZL;
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "detectCardType: " + aidNames[i] + " failed: " + e.getMessage());
            }
        }

        // AID 선택 없이 직접 다양한 명령어 시도
        Log.d(TAG, "detectCardType: Trying direct commands without AID selection...");

        // 다양한 잔액 조회 명령어 시도
        byte[][] directCommands = {
                // T-money 잔액 조회
                {(byte) 0x90, (byte) 0x4C, 0x00, 0x00, 0x04},
                // GET DATA
                {(byte) 0x00, (byte) 0xCA, 0x00, 0x00, 0x00},
                // READ BINARY
                {(byte) 0x00, (byte) 0xB0, 0x00, 0x00, 0x10},
                // READ RECORD
                {(byte) 0x00, (byte) 0xB2, 0x01, 0x04, 0x00},
                // SELECT MF
                {(byte) 0x00, (byte) 0xA4, 0x00, 0x00, 0x02, 0x3F, 0x00},
                // GET CHALLENGE
                {(byte) 0x00, (byte) 0x84, 0x00, 0x00, 0x08},
                // EZL 추정 명령어들
                {(byte) 0x90, (byte) 0x5C, 0x00, 0x00, 0x04},
                {(byte) 0x90, (byte) 0x6C, 0x00, 0x00, 0x04},
                {(byte) 0x90, (byte) 0x32, 0x00, 0x00, 0x04},
                // Felica 스타일 명령어
                {(byte) 0xFF, (byte) 0x00, 0x00, 0x00, 0x04},
        };

        String[] cmdNames = {
                "T-money Balance", "GET DATA", "READ BINARY", "READ RECORD",
                "SELECT MF", "GET CHALLENGE", "EZL-5C", "EZL-6C", "EZL-32", "Felica-style"
        };

        for (int i = 0; i < directCommands.length; i++) {
            try {
                Log.d(TAG, "detectCardType: Trying " + cmdNames[i] + " = " + bytesToHex(directCommands[i]));
                byte[] response = isoDep.transceive(directCommands[i]);
                Log.d(TAG, "detectCardType: " + cmdNames[i] + " response = " + bytesToHex(response));

                if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;
                    Log.d(TAG, "detectCardType: " + cmdNames[i] + " SW=" + String.format("%02X%02X", sw1, sw2));

                    // 성공적인 응답이면 데이터 분석
                    if (sw1 == 0x90 && sw2 == 0x00 && response.length > 2) {
                        Log.i(TAG, "detectCardType: " + cmdNames[i] + " succeeded with data!");

                        // 잔액 파싱 시도
                        if (response.length >= 6) {
                            int balanceLE = ByteBuffer.wrap(response, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                            int balanceBE = ByteBuffer.wrap(response, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                            Log.i(TAG, "detectCardType: Possible balance (LE): " + balanceLE);
                            Log.i(TAG, "detectCardType: Possible balance (BE): " + balanceBE);
                        }
                        return CardType.EZL;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "detectCardType: " + cmdNames[i] + " failed: " + e.getMessage());
            }
        }

        // Fallback to ID-based detection
        Log.w(TAG, "detectCardType: No AID matched, falling back to ID-based detection");
        return detectCardTypeFromId(cardId);
    }

    private int kftcBalance = 0;
    private String kftcCardNumber = null;

    /**
     * EZL/KFTC 카드 잔액 및 카드번호 읽기 (최적화 버전)
     * 작동 확인된 시퀀스: Secondary AID 선택 → 90 4C 잔액 조회
     */
    private void tryKftcCommands(IsoDep isoDep) {
        Log.d(TAG, "tryKftcCommands: Starting optimized KFTC balance read");

        // 1. 먼저 검증된 방법 시도: Secondary AID (D4100000140001) 선택 후 잔액 조회
        try {
            byte[] secondaryAid = {(byte)0xD4, 0x10, 0x00, 0x00, 0x14, 0x00, 0x01};
            byte[] response = selectAID(isoDep, secondaryAid);

            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "tryKftcCommands: Secondary AID selected!");

                    // FCI 응답에서 카드번호 추출 시도
                    if (response.length > 2) {
                        extractCardNumberFromFci(response, response.length - 2);
                    }

                    // T-money 스타일 잔액 조회 (90 4C 00 00 04)
                    byte[] balCmd = {(byte)0x90, 0x4C, 0x00, 0x00, 0x04};
                    byte[] balResp = isoDep.transceive(balCmd);
                    Log.d(TAG, "tryKftcCommands: Balance response = " + bytesToHex(balResp));

                    if (balResp != null && balResp.length >= 6) {
                        int bsw1 = balResp[balResp.length - 2] & 0xFF;
                        int bsw2 = balResp[balResp.length - 1] & 0xFF;

                        if (bsw1 == 0x90 && bsw2 == 0x00) {
                            // 4바이트 Big Endian 잔액
                            kftcBalance = ((balResp[0] & 0xFF) << 24) |
                                         ((balResp[1] & 0xFF) << 16) |
                                         ((balResp[2] & 0xFF) << 8) |
                                         (balResp[3] & 0xFF);
                            Log.i(TAG, "tryKftcCommands: SUCCESS! Balance = " + kftcBalance + "원");

                            // 카드번호 읽기 시도 (여러 방법)
                            tryReadCardNumber(isoDep);
                            return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryKftcCommands: Primary method failed: " + e.getMessage());
        }

        // 2. Fallback: 직접 90 4C 명령어 시도 (AID 이미 선택된 경우)
        try {
            byte[] balCmd = {(byte)0x90, 0x4C, 0x00, 0x00, 0x04};
            byte[] response = isoDep.transceive(balCmd);

            if (response != null && response.length >= 6) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    kftcBalance = ((response[0] & 0xFF) << 24) |
                                 ((response[1] & 0xFF) << 16) |
                                 ((response[2] & 0xFF) << 8) |
                                 (response[3] & 0xFF);
                    Log.i(TAG, "tryKftcCommands: Fallback SUCCESS! Balance = " + kftcBalance + "원");
                    tryReadCardNumber(isoDep);
                    return;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryKftcCommands: Fallback failed: " + e.getMessage());
        }

        Log.d(TAG, "tryKftcCommands: Final balance = " + kftcBalance);
    }

    /**
     * 카드번호 읽기 시도 (여러 방법으로)
     */
    private void tryReadCardNumber(IsoDep isoDep) {
        if (kftcCardNumber != null) return;

        // 방법 1: SFI 1, Record 1 읽기
        try {
            byte[] cmd = {0x00, (byte)0xB2, 0x01, 0x0C, 0x00};
            byte[] response = isoDep.transceive(cmd);

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.d(TAG, "tryReadCardNumber: SFI1 Rec1 = " + bytesToHex(response));
                    extractCardNumberFromTlv(response, response.length - 2);
                    if (kftcCardNumber != null) return;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryReadCardNumber: SFI1 failed: " + e.getMessage());
        }

        // 방법 2: SFI 2, Record 1 읽기
        try {
            byte[] cmd = {0x00, (byte)0xB2, 0x01, 0x14, 0x00};
            byte[] response = isoDep.transceive(cmd);

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.d(TAG, "tryReadCardNumber: SFI2 Rec1 = " + bytesToHex(response));
                    extractCardNumberFromTlv(response, response.length - 2);
                    if (kftcCardNumber != null) return;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryReadCardNumber: SFI2 failed: " + e.getMessage());
        }

        // 방법 3: GET DATA 명령 (90 4A)
        try {
            byte[] cmd = {(byte)0x90, 0x4A, 0x00, 0x00, 0x10};
            byte[] response = isoDep.transceive(cmd);

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00 && response.length >= 10) {
                    Log.d(TAG, "tryReadCardNumber: GET DATA = " + bytesToHex(response));
                    // 응답에서 직접 카드번호 추출 (BCD 인코딩)
                    String cardNum = formatCardNumber(response, 0, 8);
                    if (cardNum != null && cardNum.length() >= 16) {
                        kftcCardNumber = cardNum;
                        Log.i(TAG, "tryReadCardNumber: Found from GET DATA = " + kftcCardNumber);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryReadCardNumber: GET DATA failed: " + e.getMessage());
        }

        // 방법 4: GET DATA 명령 (00 CA)
        try {
            byte[] cmd = {0x00, (byte)0xCA, 0x00, 0x00, 0x00};
            byte[] response = isoDep.transceive(cmd);

            if (response != null && response.length > 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;

                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.d(TAG, "tryReadCardNumber: GET DATA (00CA) = " + bytesToHex(response));
                    extractCardNumberFromTlv(response, response.length - 2);
                    if (kftcCardNumber != null) return;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryReadCardNumber: GET DATA (00CA) failed: " + e.getMessage());
        }
    }

    /**
     * FCI 응답에서 카드번호 추출
     */
    private void extractCardNumberFromFci(byte[] data, int length) {
        // 먼저 전체 데이터를 스캔해서 카드번호 TLV 찾기
        for (int i = 0; i < length - 2; i++) {
            int tag = data[i] & 0xFF;

            // Tag 5A (Application PAN)
            if (tag == 0x5A && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (i + 2 + len <= length && len >= 8) {
                    kftcCardNumber = formatCardNumber(data, i + 2, len);
                    Log.i(TAG, "extractCardNumberFromFci: Found PAN (5A) = " + kftcCardNumber);
                    return;
                }
            }

            // Tag 57 (Track 2 Equivalent Data)
            if (tag == 0x57 && i + 1 < length) {
                int len = data[i + 1] & 0xFF;
                if (i + 2 + len <= length && len >= 8) {
                    kftcCardNumber = formatCardNumberFromTrack2(data, i + 2, len);
                    Log.i(TAG, "extractCardNumberFromFci: Found Track2 (57) = " + kftcCardNumber);
                    return;
                }
            }

            // Tag 9F6B (Track 2 Data)
            if (tag == 0x9F && i + 1 < length && (data[i + 1] & 0xFF) == 0x6B && i + 2 < length) {
                int len = data[i + 2] & 0xFF;
                if (i + 3 + len <= length && len >= 8) {
                    kftcCardNumber = formatCardNumberFromTrack2(data, i + 3, len);
                    Log.i(TAG, "extractCardNumberFromFci: Found Track2 (9F6B) = " + kftcCardNumber);
                    return;
                }
            }

            // 2-byte tags (9F로 시작하는 태그 처리)
            if (tag == 0x9F && i + 2 < length) {
                i++; // Skip second byte of tag
            }
        }
    }

    /**
     * TLV 데이터에서 카드번호 추출
     */
    private void extractCardNumberFromTlv(byte[] data, int length) {
        extractCardNumberFromFci(data, length);
    }

    /**
     * BCD 인코딩된 카드번호를 포맷팅
     * @return "1041 0590 3099 9789" 형식
     */
    private String formatCardNumber(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            int high = (data[offset + i] >> 4) & 0x0F;
            int low = data[offset + i] & 0x0F;
            if (high <= 9) sb.append(high);
            if (low <= 9) sb.append(low);
        }

        // 4자리씩 공백으로 구분
        String raw = sb.toString();
        if (raw.length() >= 16) {
            return raw.substring(0, 4) + " " + raw.substring(4, 8) + " " +
                   raw.substring(8, 12) + " " + raw.substring(12, 16);
        }
        return raw.length() > 0 ? raw : null;
    }

    /**
     * Track 2 데이터에서 카드번호 추출 및 포맷팅
     */
    private String formatCardNumberFromTrack2(byte[] data, int offset, int len) {
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

    public String getKftcCardNumber() {
        return kftcCardNumber;
    }

    private CardType detectTmoneyBasedCard(IsoDep isoDep, byte[] cardId) {
        // Seoul City Pass and Korea Tour Card use T-money platform
        // but have distinctive characteristics

        // This would need actual card testing to implement proper detection
        // For now, return UNKNOWN to default to TMONEY
        return CardType.UNKNOWN;
    }

    private CardType detectCardTypeFromId(byte[] cardId) {
        // This is a simplified detection based on common patterns
        // Real implementation would need more sophisticated detection
        return CardType.UNKNOWN;
    }

    private byte[] selectAID(IsoDep isoDep, byte[] aid) {
        byte[] selectCommand = new byte[6 + aid.length];
        selectCommand[0] = 0x00; // CLA
        selectCommand[1] = (byte) 0xA4; // INS (SELECT)
        selectCommand[2] = 0x04; // P1
        selectCommand[3] = 0x00; // P2
        selectCommand[4] = (byte) aid.length; // Lc
        System.arraycopy(aid, 0, selectCommand, 5, aid.length);
        selectCommand[selectCommand.length - 1] = 0x00; // Le

        try {
            Log.d(TAG, "selectAID: Sending SELECT command for AID: " + bytesToHex(aid));
            Log.d(TAG, "selectAID: Command = " + bytesToHex(selectCommand));
            byte[] response = isoDep.transceive(selectCommand);
            Log.d(TAG, "selectAID: Response = " + bytesToHex(response));
            return response;
        } catch (Exception e) {
            Log.e(TAG, "selectAID: Error selecting AID " + bytesToHex(aid), e);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
}
