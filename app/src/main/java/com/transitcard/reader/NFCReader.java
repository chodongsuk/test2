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
            if (mifareClassic != null) {
                TransitCardData result = readMifareClassicCard(mifareClassic, id);
                if (result != null) {
                    return result;
                }
            }

            // Try IsoDep (most common for Korean transit cards)
            IsoDep isoDep = IsoDep.get(tag);
            Log.d(TAG, "readCard: IsoDep available = " + (isoDep != null));
            if (isoDep != null) {
                return readIsoDepCard(isoDep, id);
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
                    } catch (Exception ignored) {}

                    if (!authenticated) {
                        try {
                            if (mifareClassic.authenticateSectorWithKeyB(sector, keysToTry[k])) {
                                authenticated = true;
                                usedKey = keyNames[k] + "(B)";
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (authenticated) {
                    int firstBlock = mifareClassic.sectorToBlock(sector);
                    int blockCount = mifareClassic.getBlockCountInSector(sector);

                    for (int i = 0; i < blockCount - 1; i++) { // 마지막 블록은 트레일러
                        int blockIndex = firstBlock + i;
                        try {
                            byte[] blockData = mifareClassic.readBlock(blockIndex);
                            String hexData = bytesToHex(blockData);

                            // 데이터가 있는 블록만 로그 출력
                            if (!hexData.equals("00000000000000000000000000000000")) {
                                Log.i(TAG, "readMifareClassicCard: [Sector " + sector + "] Block " + blockIndex + " (" + usedKey + ") = " + hexData);

                                // 잔액 파싱 시도 - 다양한 위치와 형식 시도
                                tryParseBalance(blockData, sector, blockIndex);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "readMifareClassicCard: Error reading sector " + sector + " block " + blockIndex, e);
                        }
                    }
                } else {
                    Log.d(TAG, "readMifareClassicCard: Sector " + sector + " - authentication failed with all keys");
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

        // AID 선택 없이 직접 잔액 읽기 시도 (일부 카드는 AID 선택 없이 동작)
        Log.d(TAG, "detectCardType: Trying direct read without AID selection...");
        try {
            // T-money 잔액 조회 명령어 시도
            byte[] balanceCmd = new byte[]{(byte) 0x90, (byte) 0x4C, 0x00, 0x00, 0x04};
            Log.d(TAG, "detectCardType: Direct balance command = " + bytesToHex(balanceCmd));
            byte[] response = isoDep.transceive(balanceCmd);
            Log.d(TAG, "detectCardType: Direct balance response = " + bytesToHex(response));
            if (response != null && response.length >= 4) {
                int sw1 = response[response.length - 2] & 0xFF;
                int sw2 = response[response.length - 1] & 0xFF;
                if (sw1 == 0x90 && sw2 == 0x00) {
                    Log.i(TAG, "detectCardType: Direct read succeeded, treating as EZL");
                    return CardType.EZL;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "detectCardType: Direct read failed: " + e.getMessage());
        }

        // Fallback to ID-based detection
        Log.w(TAG, "detectCardType: No AID matched, falling back to ID-based detection");
        return detectCardTypeFromId(cardId);
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
