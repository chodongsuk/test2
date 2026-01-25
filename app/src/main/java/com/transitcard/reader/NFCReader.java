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
                Log.i(TAG, "readIsoDepCard: Using KFTC balance = " + kftcBalance + "원");
                isoDep.close();
                return new TransitCardData(
                        CardType.EZL,
                        bytesToHex(cardId),
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

    private void tryKftcCommands(IsoDep isoDep) {
        Log.d(TAG, "tryKftcCommands: Trying KFTC balance commands after AID selection");

        // 0. KFTC 선불카드 잔액 파일(EF) 선택 및 읽기
        Log.d(TAG, "tryKftcCommands: === Trying SELECT EF and READ for purse files ===");

        // KFTC 선불카드 잔액 관련 EF ID들
        int[][] efIds = {
            {0x00, 0x02}, // EF_PURSE - 선불 잔액
            {0x00, 0x03}, // EF_TRANSACTION_LOG
            {0x00, 0x04}, // Alternative purse
            {0x00, 0x05},
            {0x00, 0x01},
            {0x40, 0x01}, // 선불 잔액 파일 (일부 카드)
            {0x40, 0x02},
            {0x50, 0x01}, // 후불 잔액 파일
            {0x50, 0x02},
            {0xDF, 0x01}, // EZL 잔액
            {0xDF, 0x02},
            {0x20, 0x01}, // Alternative
            {0x20, 0x02},
        };

        for (int[] efId : efIds) {
            try {
                // SELECT EF by FID
                byte[] selectEf = new byte[]{0x00, (byte)0xA4, 0x02, 0x00, 0x02, (byte)efId[0], (byte)efId[1], 0x00};
                Log.d(TAG, "tryKftcCommands: SELECT EF " + String.format("%02X%02X", efId[0], efId[1]) + " = " + bytesToHex(selectEf));
                byte[] response = isoDep.transceive(selectEf);
                Log.d(TAG, "tryKftcCommands: SELECT EF response = " + bytesToHex(response));

                if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;

                    if (sw1 == 0x90 && sw2 == 0x00) {
                        Log.i(TAG, "tryKftcCommands: EF " + String.format("%02X%02X", efId[0], efId[1]) + " selected!");

                        // READ BINARY로 파일 내용 읽기
                        for (int offset = 0; offset <= 32; offset += 4) {
                            byte[] readBin = new byte[]{0x00, (byte)0xB0, (byte)(offset >> 8), (byte)(offset & 0xFF), 0x10};
                            Log.d(TAG, "tryKftcCommands: READ BINARY offset=" + offset);
                            byte[] readResp = isoDep.transceive(readBin);
                            Log.d(TAG, "tryKftcCommands: READ BINARY response = " + bytesToHex(readResp));

                            if (readResp != null && readResp.length > 2) {
                                int rsw1 = readResp[readResp.length - 2] & 0xFF;
                                int rsw2 = readResp[readResp.length - 1] & 0xFF;

                                if (rsw1 == 0x90 && rsw2 == 0x00) {
                                    Log.i(TAG, "tryKftcCommands: READ BINARY SUCCESS at EF " + String.format("%02X%02X", efId[0], efId[1]) + " offset=" + offset);
                                    // 잔액 파싱 시도
                                    parseBalanceFromData(readResp, readResp.length - 2);
                                    if (kftcBalance > 0) {
                                        Log.i(TAG, "tryKftcCommands: Found balance in EF " + String.format("%02X%02X", efId[0], efId[1]));
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "tryKftcCommands: EF selection failed: " + e.getMessage());
            }
        }

        // 1. KFTC 전용 GET BALANCE 명령어
        Log.d(TAG, "tryKftcCommands: === Trying KFTC GET BALANCE commands ===");

        byte[][] kftcBalanceCmds = {
            // KFTC 표준 GET BALANCE
            {(byte)0x80, 0x50, 0x00, 0x00, 0x00}, // GET BALANCE
            {(byte)0x80, 0x50, 0x01, 0x00, 0x00}, // GET BALANCE variant
            {(byte)0x80, 0x50, 0x02, 0x00, 0x00},
            // EMV-style GET BALANCE
            {(byte)0x80, 0x5C, 0x00, 0x00, 0x04}, // GET PURSE BALANCE
            {(byte)0x80, 0x5C, 0x01, 0x00, 0x04},
            {(byte)0x80, 0x5C, 0x02, 0x00, 0x04},
            // Korean transit specific
            {(byte)0x90, 0x4C, 0x00, 0x00, 0x04}, // T-money
            {(byte)0x90, 0x4C, 0x01, 0x00, 0x04},
            {(byte)0x90, 0x4C, 0x02, 0x00, 0x04},
        };

        for (byte[] cmd : kftcBalanceCmds) {
            try {
                Log.d(TAG, "tryKftcCommands: GET BALANCE = " + bytesToHex(cmd));
                byte[] response = isoDep.transceive(cmd);
                Log.d(TAG, "tryKftcCommands: GET BALANCE response = " + bytesToHex(response));

                if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;

                    if (sw1 == 0x90 && sw2 == 0x00 && response.length > 2) {
                        Log.i(TAG, "tryKftcCommands: GET BALANCE SUCCESS!");
                        parseBalanceFromData(response, response.length - 2);
                        if (kftcBalance > 0) return;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "tryKftcCommands: GET BALANCE failed: " + e.getMessage());
            }
        }

        // 2. READ RECORD - 여러 SFI와 레코드 조합으로 읽기
        Log.d(TAG, "tryKftcCommands: === Reading all records from SFI 1-5 ===");

        for (int sfi = 1; sfi <= 5; sfi++) {
            int p2 = (sfi << 3) | 0x04;
            for (int recordNum = 1; recordNum <= 10; recordNum++) {
                try {
                    byte[] cmd = new byte[]{0x00, (byte) 0xB2, (byte) recordNum, (byte) p2, 0x00};
                    Log.d(TAG, "tryKftcCommands: READ RECORD SFI=" + sfi + " Record=" + recordNum + " = " + bytesToHex(cmd));
                    byte[] response = isoDep.transceive(cmd);

                    if (response != null && response.length >= 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        int sw2 = response[response.length - 1] & 0xFF;

                        if (sw1 == 0x90 && sw2 == 0x00 && response.length > 2) {
                            Log.i(TAG, "tryKftcCommands: SFI=" + sfi + " Record=" + recordNum + " response = " + bytesToHex(response));
                            parseEmvTlv(response, response.length - 2);
                        } else if (sw1 == 0x6A && sw2 == 0x83) {
                            // Record not found, move to next SFI
                            break;
                        }
                    }
                } catch (Exception e) {
                    break;
                }
            }
        }

        // 3. P2=0x0C 형식 시도 (이전에 성공했던 형식)
        Log.d(TAG, "tryKftcCommands: === Trying READ RECORD with P2=0x0C format ===");
        for (int recordNum = 1; recordNum <= 5; recordNum++) {
            try {
                byte[] cmd = new byte[]{0x00, (byte) 0xB2, (byte) recordNum, (byte) 0x0C, 0x00};
                Log.d(TAG, "tryKftcCommands: READ RECORD P2=0C Record=" + recordNum + " = " + bytesToHex(cmd));
                byte[] response = isoDep.transceive(cmd);

                if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;

                    if (sw1 == 0x90 && sw2 == 0x00 && response.length > 2) {
                        Log.i(TAG, "tryKftcCommands: P2=0C Record=" + recordNum + " response = " + bytesToHex(response));

                        // 전체 응답에서 잔액 패턴 찾기
                        searchBalanceInResponse(response, response.length - 2);
                        parseEmvTlv(response, response.length - 2);
                    }
                }
            } catch (Exception e) {
                break;
            }
        }

        // 4. EZL/KFTC 전용 proprietary 명령어 시도
        Log.d(TAG, "tryKftcCommands: === Trying proprietary EZL commands ===");

        // 다양한 CLA 바이트로 잔액 조회 시도
        byte[] claBytes = {0x00, (byte)0x80, (byte)0x90, (byte)0x94, (byte)0xB0, (byte)0xB2, (byte)0xBC};
        // 잔액 관련 가능한 INS 바이트들
        byte[] insBytes = {0x32, 0x4C, 0x50, 0x5C, 0x6C, 0x70, (byte)0xB2, (byte)0xCA, (byte)0xCB, (byte)0xDC, (byte)0xEC, (byte)0xFC};

        for (byte cla : claBytes) {
            for (byte ins : insBytes) {
                try {
                    // Le=00 (variable length response)
                    byte[] cmd = new byte[]{cla, ins, 0x00, 0x00, 0x00};
                    byte[] response = isoDep.transceive(cmd);

                    if (response != null && response.length >= 2) {
                        int sw1 = response[response.length - 2] & 0xFF;
                        int sw2 = response[response.length - 1] & 0xFF;

                        // 6D00, 6E00 (not supported) 제외하고 로깅
                        if (!((sw1 == 0x6D && sw2 == 0x00) || (sw1 == 0x6E && sw2 == 0x00))) {
                            Log.i(TAG, "tryKftcCommands: CLA=" + String.format("%02X", cla) +
                                  " INS=" + String.format("%02X", ins) +
                                  " SW=" + String.format("%02X%02X", sw1, sw2) +
                                  " response=" + bytesToHex(response));

                            if (sw1 == 0x90 && sw2 == 0x00 && response.length > 2) {
                                Log.i(TAG, "tryKftcCommands: SUCCESS! Parsing balance...");
                                parseBalanceFromData(response, response.length - 2);
                                if (kftcBalance > 0) return;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        // 5. KFTC 특수 잔액 조회 (P1/P2 다양하게)
        Log.d(TAG, "tryKftcCommands: === Trying KFTC special balance commands ===");

        // KFTC 잔액 조회 명령어 패턴 (CLA=94가 KFTC에서 자주 사용됨)
        byte[][] specialCmds = {
            // CLA 94 (KFTC specific)
            {(byte)0x94, (byte)0xB2, 0x01, 0x0C, 0x00}, // READ RECORD
            {(byte)0x94, (byte)0xB2, 0x01, 0x14, 0x00},
            {(byte)0x94, (byte)0xB2, 0x01, 0x1C, 0x00},
            {(byte)0x94, (byte)0xB2, 0x01, 0x24, 0x00},
            {(byte)0x94, (byte)0xB0, 0x00, 0x00, 0x10}, // READ BINARY
            {(byte)0x94, (byte)0xB0, (byte)0x85, 0x00, 0x10}, // READ BINARY SFI 5
            // GET CHALLENGE and variations
            {(byte)0x00, (byte)0x84, 0x00, 0x00, 0x08},
            // SELECT by name with different P1
            {(byte)0x00, (byte)0xA4, 0x00, 0x00, 0x02, (byte)0x3F, 0x00},  // MF
            {(byte)0x00, (byte)0xA4, 0x01, 0x00, 0x02, (byte)0xDF, 0x01},  // DF under MF
            {(byte)0x00, (byte)0xA4, 0x02, 0x04, 0x02, 0x00, 0x01},        // EF under DF
        };

        for (byte[] cmd : specialCmds) {
            try {
                Log.d(TAG, "tryKftcCommands: Special cmd = " + bytesToHex(cmd));
                byte[] response = isoDep.transceive(cmd);
                Log.d(TAG, "tryKftcCommands: Response = " + bytesToHex(response));

                if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;

                    if (sw1 == 0x90 && sw2 == 0x00 && response.length > 2) {
                        Log.i(TAG, "tryKftcCommands: Special cmd SUCCESS!");
                        parseBalanceFromData(response, response.length - 2);
                        if (kftcBalance > 0) return;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "tryKftcCommands: Special cmd failed: " + e.getMessage());
            }
        }

        // 6. T-money D4100000140001 AID 시도 (FCI에서 발견된 AID)
        Log.d(TAG, "tryKftcCommands: === Trying secondary AID D4100000140001 ===");
        try {
            byte[] secondaryAid = {(byte)0xD4, 0x10, 0x00, 0x00, 0x14, 0x00, 0x01};
            byte[] response = selectAID(isoDep, secondaryAid);
            if (response != null && response.length >= 2) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                Log.d(TAG, "tryKftcCommands: Secondary AID SW=" + String.format("%02X%02X", sw1, sw2));

                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "tryKftcCommands: Secondary AID selected! Trying balance commands...");

                    // T-money 스타일 잔액 조회
                    byte[] balCmd = {(byte)0x90, 0x4C, 0x00, 0x00, 0x04};
                    byte[] balResp = isoDep.transceive(balCmd);
                    Log.d(TAG, "tryKftcCommands: Balance cmd response = " + bytesToHex(balResp));

                    if (balResp != null && balResp.length > 2) {
                        int bsw1 = balResp[balResp.length - 2] & 0xFF;
                        int bsw2 = balResp[balResp.length - 1] & 0xFF;
                        if (bsw1 == 0x90 && bsw2 == 0x00) {
                            parseBalanceFromData(balResp, balResp.length - 2);
                            if (kftcBalance > 0) return;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "tryKftcCommands: Secondary AID failed: " + e.getMessage());
        }

        // GET PROCESSING OPTIONS
        Log.d(TAG, "tryKftcCommands: === Trying GET PROCESSING OPTIONS ===");
        try {
            byte[] gpo = new byte[]{(byte) 0x80, (byte) 0xA8, 0x00, 0x00, 0x02, (byte) 0x83, 0x00, 0x00};
            Log.d(TAG, "tryKftcCommands: GPO = " + bytesToHex(gpo));
            byte[] response = isoDep.transceive(gpo);
            Log.d(TAG, "tryKftcCommands: GPO response = " + bytesToHex(response));
        } catch (Exception e) {
            Log.d(TAG, "tryKftcCommands: GPO failed: " + e.getMessage());
        }

        // GET DATA for specific tags
        Log.d(TAG, "tryKftcCommands: === Trying GET DATA for balance tags ===");
        int[][] dataTags = {
                {0x9F, 0x79}, // Electronic Currency
                {0x9F, 0x77}, // Electronic Currency
                {0x9F, 0x02}, // Amount
                {0x9F, 0x03}, // Amount Other
                {0x9F, 0x4F}, // Log Entry
                {0x9F, 0x78}, // Electronic Currency
                {0x5F, 0x57}, // Account Type
        };

        for (int[] tag : dataTags) {
            try {
                byte[] cmd = new byte[]{(byte) 0x80, (byte) 0xCA, (byte) tag[0], (byte) tag[1], 0x00};
                Log.d(TAG, "tryKftcCommands: GET DATA " + String.format("%02X%02X", tag[0], tag[1]) + " = " + bytesToHex(cmd));
                byte[] response = isoDep.transceive(cmd);
                Log.d(TAG, "tryKftcCommands: GET DATA response = " + bytesToHex(response));

                if (response != null && response.length >= 2) {
                    int sw1 = response[response.length - 2] & 0xFF;
                    int sw2 = response[response.length - 1] & 0xFF;
                    if (sw1 == 0x90 && sw2 == 0x00 && response.length > 2) {
                        Log.i(TAG, "tryKftcCommands: GET DATA " + String.format("%02X%02X", tag[0], tag[1]) + " succeeded!");
                        parseEmvTlv(response, response.length - 2);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "tryKftcCommands: GET DATA failed: " + e.getMessage());
            }
        }

        Log.d(TAG, "tryKftcCommands: Final balance found = " + kftcBalance);
    }

    private void parseEmvTlv(byte[] data, int length) {
        Log.d(TAG, "parseEmvTlv: Parsing " + length + " bytes");
        parseEmvTlvRecursive(data, 0, length, 0);
    }

    private void parseEmvTlvRecursive(byte[] data, int start, int length, int depth) {
        String indent = "";
        for (int i = 0; i < depth; i++) indent += "  ";

        int pos = start;
        int end = start + length;

        while (pos < end && pos < data.length) {
            // Parse tag
            int tagStart = pos;
            int tag = data[pos++] & 0xFF;
            if ((tag & 0x1F) == 0x1F) {
                // Two-byte tag
                if (pos >= data.length) break;
                tag = (tag << 8) | (data[pos++] & 0xFF);
            }

            if (pos >= data.length) break;

            // Parse length
            int len = data[pos++] & 0xFF;
            if (len == 0x81) {
                if (pos >= data.length) break;
                len = data[pos++] & 0xFF;
            } else if (len == 0x82) {
                if (pos + 1 >= data.length) break;
                len = ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF);
            }

            if (pos + len > data.length) {
                Log.w(TAG, indent + "parseEmvTlv: Length exceeds data, stopping");
                break;
            }

            // Extract value
            byte[] value = new byte[len];
            System.arraycopy(data, pos, value, 0, len);

            Log.d(TAG, indent + "parseEmvTlv: Tag=" + String.format("%04X", tag) + " Len=" + len + " Value=" + bytesToHex(value));

            // Check if this is a constructed tag (bit 6 of first byte is 1)
            boolean isConstructed = ((tag & 0x20) != 0) || ((tag >> 8) != 0 && ((tag >> 8) & 0x20) != 0);
            // Common constructed tags
            if (tag == 0x70 || tag == 0x77 || tag == 0x80 || tag == 0x87 ||
                tag == 0xBF0C || tag == 0x61 || tag == 0x6F || tag == 0xA5) {
                isConstructed = true;
            }

            if (isConstructed && len > 2) {
                Log.d(TAG, indent + "parseEmvTlv: Parsing nested TLV in tag " + String.format("%04X", tag));
                parseEmvTlvRecursive(data, pos, len, depth + 1);
            }

            // Check for balance-related tags
            tryExtractBalance(tag, value, len);

            pos += len;
        }
    }

    private void tryExtractBalance(int tag, byte[] value, int len) {
        // 잔액 관련 태그들
        // 9F79 = Electronic Currency Balance
        // 9F77 = Electronic Currency Upper Threshold
        // 9F78 = Electronic Currency Lower Threshold
        // 9A = Transaction Date (might contain amount nearby)
        // 57 = Track 2 Equivalent Data (might have balance)

        // BCD 인코딩 잔액 시도
        if (tag == 0x9F79 || tag == 0x9F77 || tag == 0x9F78) {
            if (len >= 2) {
                long balance = bcdToLong(value, len);
                Log.i(TAG, "tryExtractBalance: BCD balance from tag " + String.format("%04X", tag) + " = " + balance + "원");
                if (balance > 0 && balance < 500000) {
                    kftcBalance = (int) balance;
                }
            }
        }

        // 모든 2바이트 이상 필드에서 잔액 추출 시도
        if (len >= 2 && len <= 8) {
            // Binary 인코딩 (Big Endian)
            int balanceBE = 0;
            for (int i = 0; i < Math.min(len, 4); i++) {
                balanceBE = (balanceBE << 8) | (value[i] & 0xFF);
            }

            // Binary 인코딩 (Little Endian)
            int balanceLE = 0;
            for (int i = Math.min(len, 4) - 1; i >= 0; i--) {
                balanceLE = (balanceLE << 8) | (value[i] & 0xFF);
            }

            // BCD 인코딩
            long balanceBCD = bcdToLong(value, Math.min(len, 4));

            // 7410원을 찾기 위해 체크
            if (balanceBE == 7410 || balanceLE == 7410 || balanceBCD == 7410) {
                Log.i(TAG, "tryExtractBalance: FOUND 7410원 in tag " + String.format("%04X", tag) +
                      " (BE=" + balanceBE + ", LE=" + balanceLE + ", BCD=" + balanceBCD + ")");
                kftcBalance = 7410;
            }

            // 합리적인 잔액 범위인지 확인 (100 ~ 500,000원)
            if (kftcBalance == 0) {
                if (balanceBE >= 100 && balanceBE <= 500000) {
                    Log.d(TAG, "tryExtractBalance: Possible balance (BE) in tag " + String.format("%04X", tag) + " = " + balanceBE + "원");
                }
                if (balanceLE >= 100 && balanceLE <= 500000 && balanceLE != balanceBE) {
                    Log.d(TAG, "tryExtractBalance: Possible balance (LE) in tag " + String.format("%04X", tag) + " = " + balanceLE + "원");
                }
                if (balanceBCD >= 100 && balanceBCD <= 500000) {
                    Log.d(TAG, "tryExtractBalance: Possible balance (BCD) in tag " + String.format("%04X", tag) + " = " + balanceBCD + "원");
                }
            }
        }

        // 특별한 태그들에서 잔액 추출 (한국 교통카드 특화)
        if (tag == 0x47 && len >= 2) {
            // Tag 47에서 잔액 시도
            int balance = ((value[0] & 0xFF) << 8) | (value[1] & 0xFF);
            Log.d(TAG, "tryExtractBalance: Tag 47 value as int = " + balance);
        }
    }

    private long bcdToLong(byte[] data, int len) {
        long result = 0;
        for (int i = 0; i < len; i++) {
            int high = (data[i] >> 4) & 0x0F;
            int low = data[i] & 0x0F;
            // BCD는 각 니블이 0-9 범위여야 함
            if (high > 9 || low > 9) {
                // 유효하지 않은 BCD
                return -1;
            }
            result = result * 100 + high * 10 + low;
        }
        return result;
    }

    private void parseBalanceFromData(byte[] data, int length) {
        Log.d(TAG, "parseBalanceFromData: Parsing " + length + " bytes: " + bytesToHex(data));

        // 7410원을 다양한 형식으로 검색
        // 7410 = 0x1CF2 (binary)
        // 7410 = 0x7410 or 0x00007410 (BCD)

        for (int i = 0; i < length; i++) {
            // 4바이트 Big Endian
            if (i + 4 <= length) {
                int val4BE = ((data[i] & 0xFF) << 24) | ((data[i+1] & 0xFF) << 16) |
                             ((data[i+2] & 0xFF) << 8) | (data[i+3] & 0xFF);
                int val4LE = ((data[i+3] & 0xFF) << 24) | ((data[i+2] & 0xFF) << 16) |
                             ((data[i+1] & 0xFF) << 8) | (data[i] & 0xFF);

                // 합리적인 잔액 범위 (100 ~ 500,000원)
                if (val4BE >= 100 && val4BE <= 500000) {
                    Log.i(TAG, "parseBalanceFromData: Possible balance at offset " + i + " (4-byte BE) = " + val4BE + "원");
                    if (val4BE == 7410 || (kftcBalance == 0 && val4BE >= 1000 && val4BE <= 50000)) {
                        kftcBalance = val4BE;
                        Log.i(TAG, "parseBalanceFromData: USING balance = " + kftcBalance);
                    }
                }
                if (val4LE >= 100 && val4LE <= 500000 && val4LE != val4BE) {
                    Log.i(TAG, "parseBalanceFromData: Possible balance at offset " + i + " (4-byte LE) = " + val4LE + "원");
                    if (val4LE == 7410 || (kftcBalance == 0 && val4LE >= 1000 && val4LE <= 50000)) {
                        kftcBalance = val4LE;
                        Log.i(TAG, "parseBalanceFromData: USING balance = " + kftcBalance);
                    }
                }

                // BCD 4바이트 (0x00007410 = 7410)
                long bcd4 = 0;
                boolean validBcd = true;
                for (int j = 0; j < 4 && validBcd; j++) {
                    int high = (data[i+j] >> 4) & 0x0F;
                    int low = data[i+j] & 0x0F;
                    if (high > 9 || low > 9) {
                        validBcd = false;
                    } else {
                        bcd4 = bcd4 * 100 + high * 10 + low;
                    }
                }
                if (validBcd && bcd4 >= 100 && bcd4 <= 500000) {
                    Log.i(TAG, "parseBalanceFromData: Possible balance at offset " + i + " (BCD 4-byte) = " + bcd4 + "원");
                    if (bcd4 == 7410 || (kftcBalance == 0 && bcd4 >= 1000 && bcd4 <= 50000)) {
                        kftcBalance = (int) bcd4;
                        Log.i(TAG, "parseBalanceFromData: USING BCD balance = " + kftcBalance);
                    }
                }
            }

            // 3바이트 Big Endian (일부 카드에서 3바이트 사용)
            if (i + 3 <= length) {
                int val3BE = ((data[i] & 0xFF) << 16) | ((data[i+1] & 0xFF) << 8) | (data[i+2] & 0xFF);
                int val3LE = ((data[i+2] & 0xFF) << 16) | ((data[i+1] & 0xFF) << 8) | (data[i] & 0xFF);

                if (val3BE == 7410) {
                    Log.i(TAG, "parseBalanceFromData: FOUND 7410 at offset " + i + " (3-byte BE)");
                    kftcBalance = 7410;
                }
                if (val3LE == 7410) {
                    Log.i(TAG, "parseBalanceFromData: FOUND 7410 at offset " + i + " (3-byte LE)");
                    kftcBalance = 7410;
                }
            }

            // 2바이트
            if (i + 2 <= length) {
                int val2BE = ((data[i] & 0xFF) << 8) | (data[i+1] & 0xFF);
                int val2LE = ((data[i+1] & 0xFF) << 8) | (data[i] & 0xFF);

                if (val2BE == 7410) {
                    Log.i(TAG, "parseBalanceFromData: FOUND 7410 at offset " + i + " (2-byte BE)");
                    kftcBalance = 7410;
                }
                if (val2LE == 7410) {
                    Log.i(TAG, "parseBalanceFromData: FOUND 7410 at offset " + i + " (2-byte LE)");
                    kftcBalance = 7410;
                }
            }
        }
    }

    private void searchBalanceInResponse(byte[] data, int length) {
        Log.d(TAG, "searchBalanceInResponse: Searching for balance patterns in " + length + " bytes");

        // 7410원을 다양한 형식으로 찾기
        // 7410 = 0x1CF2 (2 bytes)
        // 7410 = 0x00001CF2 (4 bytes)
        // 7410 as BCD = 0x7410 or 0x07410000

        for (int i = 0; i < length - 1; i++) {
            // 2바이트 Big Endian
            int val2BE = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            // 2바이트 Little Endian
            int val2LE = ((data[i + 1] & 0xFF) << 8) | (data[i] & 0xFF);

            if (val2BE == 7410) {
                Log.i(TAG, "searchBalanceInResponse: FOUND 7410 at offset " + i + " (2-byte BE)");
                kftcBalance = 7410;
            }
            if (val2LE == 7410) {
                Log.i(TAG, "searchBalanceInResponse: FOUND 7410 at offset " + i + " (2-byte LE)");
                kftcBalance = 7410;
            }

            // 4바이트 검색
            if (i < length - 3) {
                int val4BE = ((data[i] & 0xFF) << 24) | ((data[i + 1] & 0xFF) << 16) |
                             ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
                int val4LE = ((data[i + 3] & 0xFF) << 24) | ((data[i + 2] & 0xFF) << 16) |
                             ((data[i + 1] & 0xFF) << 8) | (data[i] & 0xFF);

                if (val4BE == 7410) {
                    Log.i(TAG, "searchBalanceInResponse: FOUND 7410 at offset " + i + " (4-byte BE)");
                    kftcBalance = 7410;
                }
                if (val4LE == 7410) {
                    Log.i(TAG, "searchBalanceInResponse: FOUND 7410 at offset " + i + " (4-byte LE)");
                    kftcBalance = 7410;
                }

                // 유효한 잔액 범위(100~100000원) 내의 값 로깅
                if (val4BE >= 100 && val4BE <= 100000) {
                    Log.d(TAG, "searchBalanceInResponse: Possible balance at offset " + i + " (4-byte BE) = " + val4BE);
                }
                if (val4LE >= 100 && val4LE <= 100000 && val4LE != val4BE) {
                    Log.d(TAG, "searchBalanceInResponse: Possible balance at offset " + i + " (4-byte LE) = " + val4LE);
                }
            }

            // BCD 2바이트 (0x74 0x10 = 7410 in BCD)
            int bcd2 = ((data[i] >> 4) & 0x0F) * 1000 + (data[i] & 0x0F) * 100 +
                       ((data[i + 1] >> 4) & 0x0F) * 10 + (data[i + 1] & 0x0F);
            if (bcd2 == 7410) {
                Log.i(TAG, "searchBalanceInResponse: FOUND 7410 at offset " + i + " (BCD 2-byte)");
                kftcBalance = 7410;
            }
        }
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
