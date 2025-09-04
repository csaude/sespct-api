package mz.org.csaude.sespcet.api.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.text.SimpleDateFormat;
import java.util.*;


public class Utilities {

    public static boolean stringHasValue(String string){
        return string != null && !string.isEmpty() && string.trim().length() > 0 && !"null".equalsIgnoreCase(string.trim());
    }

    public static String ensureXCaractersOnNumber(long number, int x){
        String formatedNumber = "";
        int numberOfCharacterToIncrise = 0;

        formatedNumber = number + "";

        numberOfCharacterToIncrise = x - formatedNumber.length();

        for(int i = 0; i < numberOfCharacterToIncrise; i++) formatedNumber = "0" + formatedNumber;

        return formatedNumber;
    }

    public static String concatStrings(String currentString, String toConcant, String scapeStr){
        if (!stringHasValue(currentString)) return toConcant;

        if (!stringHasValue(toConcant)) return currentString;

        return currentString + scapeStr+ toConcant;
    }

    public static boolean isStringIn(String value, String... inValues){
        if (inValues == null || value == null) return false;

        for (String str : inValues){
            if (value.equals(str)) return true;
        }

        return false;
    }

    public static boolean listHasElements(List<?> list){
        return list != null && !list.isEmpty() && list.size() > 0;
    }

    public static boolean hasElements(Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }


    public static <T extends Object> T findOnArray(List<T> list, T toFind){
        for (T o : list) {
            if (o.equals(toFind)) return o;
        }
        return null;
    }

    public static boolean isNumeric(String str) {
        try {
            Double.parseDouble(str);
            return true;
        } catch(NumberFormatException e){
            return false;
        }
    }

    public static String generateUUID () {
        return UUID.randomUUID().toString();
    }

    public static String garantirXCaracterOnNumber(long number, int x){
        String formatedNumber = "";
        int numberOfCharacterToIncrise = 0;

        formatedNumber = number + "";

        numberOfCharacterToIncrise = x - formatedNumber.length();

        for(int i = 0; i < numberOfCharacterToIncrise; i++) formatedNumber = "0" + formatedNumber;

        return formatedNumber;
    }

    public static String formatToYYYYMMDD (Date date ) {

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

        String strDate = dateFormat.format(date);

        return strDate;
    }


    public static <T>  List<T> parseObjectToList(Object obj, Class<T> objClass_) throws InvocationTargetException, InstantiationException, IllegalAccessException, NoSuchMethodException {
        List<Object> list = new ArrayList<>();

        list.add(obj);

        return parseList(list, objClass_);
    }

    public static <T extends Object, S extends Object> List<S> parseList(List<T> list, Class<S> classe) throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        if (list == null) return null;
        List<S> parsedList = new ArrayList<S>();

        for (T t : list){
            parsedList.add(classe.getDeclaredConstructor(t.getClass()).newInstance(t));
        }
        return parsedList;
    }


    public static <T> T[] parseObjectToArray(T ...objts){
        T[] array = (T[]) Array.newInstance(objts[0].getClass(), objts.length);

        for (int i=0; i < objts.length; i++){
            array[i] = objts[i];
        }

        return array;
    }

    /**
     * Verifica se uma determinada {@link List} contem todos os elementos passados pelo parametro
     *
     * @param list lista cujo elementos se pretende verificar
     * @param o elementos a verificar
     * @return true se a lista contem todos os elementos ou false no caso contrario
     */

    public static <T> boolean containsAll(List<T> list, T ...o){
        for (T t : o){
            if (!list.contains(t)) return false;
        }

        return true;
    }

    public static int getPosOfElementOnList(List<?> list, Object toFind){
        if (!listHasElements((ArrayList<?>) list)) return -1;

        return list.indexOf(toFind);
    }

    public static <T> T findOnList(List<T> list, Object toFind){
        if (!listHasElements((ArrayList<?>) list)) return null;

        int pos = getPosOfElementOnList(list, toFind);

        return pos >= 0 ? list.get(pos) : null;
    }

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < length; i++) {
            int randomIndex = random.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(randomIndex));
        }
        return sb.toString();
    }

    public static <T extends Object, S extends Object> List<S> parse(List<T> list, Class<S> classe)  {
        if (list == null) return null;
        List<S> parsedList = new ArrayList<S>();

        for (T t : list){
            try {
                parsedList.add(classe.getDeclaredConstructor(t.getClass()).newInstance(t));
            } catch (IllegalAccessException | InstantiationException | InvocationTargetException |
                     NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        return parsedList;
    }

    public static BigDecimal roundToOneDecimalPlace(Number number) {
        BigDecimal bd = new BigDecimal(number.doubleValue());
        return bd.setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * Generates a PBKDF2WithHmacSHA256 hash of password and salt and returns it as a Base64-encoded string.
     * @param password the password to encrypt
     * @param salt random string that should be used to salt the password
     * @return Base64-encoded string of hash
     */
    public static String encryptPassword(String password, String salt) {
        final String algorithm = "PBKDF2WithHmacSHA256";
        final int iterations = 10000;
        final int keyLength = 256;

        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(), iterations, keyLength);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(algorithm);
            byte[] hash = factory.generateSecret(spec).getEncoded();

            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Generates a random salt.
     * @return Base64-encoded string of random salt
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[32];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }
}
