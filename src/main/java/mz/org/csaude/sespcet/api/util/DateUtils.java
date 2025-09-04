package mz.org.csaude.sespcet.api.util;

import jakarta.inject.Singleton;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Singleton
public class DateUtils {
    public static final String YEAR_FORMAT="YYYY";
    public static final String DAY_FORMAT="DD";
    public static final String MONTH_FORMAT="MM";
    public static final String HOUR_FORMAT="HH";
    public static final String SECOND_FORMAT="ss";
    public static final String MINUTE_FORMAT="mm";
    public static final String MILLISECOND_FORMAT="SSS";
    public static final String DDMM_DATE_FORMAT ="dd-MM-yyyy";
    public static final String MMDD_DATE_FORMAT="MM-dd-yyyy";
    public static final String DATE_TIME_FORMAT="dd-MM-yyyy HH:mm:ss";


    public static int getDayOfMonth(Date date){
        Calendar d = Calendar.getInstance();
        d.setTime(date);

        return d.get(Calendar.DAY_OF_MONTH);
    }

    public static int getDayOfWeek(Date date){
        Calendar d = Calendar.getInstance();
        d.setTime(date);

        return d.get(Calendar.DAY_OF_WEEK);
    }

    public static int getMonth(Date date){
        Calendar d = Calendar.getInstance();
        d.setTime(date);

        return d.get(Calendar.MONTH) + 1;
    }

    public static int getYear(Date date){
        Calendar d = Calendar.getInstance();
        d.setTime(date);

        return d.get(Calendar.YEAR);
    }

    public static int getHours(Date date){
        Calendar d = Calendar.getInstance();
        d.setTime(date);

        return d.get(Calendar.HOUR_OF_DAY);
    }

    public static int getMinutes(Date date){
        Calendar d = Calendar.getInstance();
        d.setTime(date);

        return d.get(Calendar.MINUTE);
    }

    public static int getSeconds(Date date){
        Calendar d = Calendar.getInstance();
        d.setTime(date);

        return d.get(Calendar.SECOND);
    }

    public static Date getCurrentDate(){
        return Calendar.getInstance().getTime();
    }

    public static double dateDiff(Date dataMaior, Date dataMenor, String dateFormat){

        double differenceMilliSeconds =  dataMaior.getTime() - dataMenor.getTime();


        if (dateFormat.equals(DateUtils.MILLISECOND_FORMAT)) return differenceMilliSeconds;

        double diferenceSeconds = differenceMilliSeconds/1000;

        if (dateFormat.equals(DateUtils.SECOND_FORMAT)) return diferenceSeconds;

        double diferenceMinutes = diferenceSeconds/60;

        if (dateFormat.equals(DateUtils.MINUTE_FORMAT)) return diferenceMinutes;

        double diferenceHours = diferenceMinutes/60;

        if (dateFormat.equals(DateUtils.HOUR_FORMAT)) return diferenceHours;

        double diferenceDays = diferenceHours/24;

        if (dateFormat.equals(DateUtils.DAY_FORMAT)) return diferenceDays;

        double diferenceMonts = diferenceDays/30;

        if (dateFormat.equals(DateUtils.MONTH_FORMAT)) return diferenceMonts;

        double diferenceYears = diferenceMonts/12;

        if (dateFormat.equals(DateUtils.YEAR_FORMAT)) return diferenceYears;

        throw new IllegalArgumentException("UNKOWN DATE FORMAT [" + dateFormat + "]");
    }

    public static String parseDateToYYYYMMDDString(Date toParse){
        SimpleDateFormat datetemp = new SimpleDateFormat("yyyy-MM-dd");
        String data = datetemp.format(toParse);
        return data;
    }


    public static Date createDate(String stringDate, String dateFormat) {
        try {
            SimpleDateFormat sDate = new SimpleDateFormat(dateFormat);
            Date date = sDate.parse(stringDate);

            return date;
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public static String formatToDDMMYYYY(Date date){
        if (date == null) return null;

        return formatToDDMMYYYY(date, "-");
    }

    public static String formatToDDMMYYYY(Date date, String separator){
        if (date == null){
            return "";
        }
        return garantirXCaracterOnNumber(getDayOfMonth(date), 2) + separator + garantirXCaracterOnNumber(getMonth(date), 2) + separator + getYear(date);
    }

    public static String garantirXCaracterOnNumber(long number, int x){
        String formatedNumber = "";
        int numberOfCharacterToIncrise = 0;

        formatedNumber = number + "";

        numberOfCharacterToIncrise = x - formatedNumber.length();

        for(int i = 0; i < numberOfCharacterToIncrise; i++) formatedNumber = "0" + formatedNumber;

        return formatedNumber;
    }

    public static double calculaIdade(String dataMenor, String format) throws ParseException {

        String dataMaior = formatToDDMMYYYY(getCurrentDate());

        try {
            Date dataMenorAux = createDate(dataMenor, "dd-MM-yyyy");
            Date dataMaiorAux = createDate(dataMaior, "dd-MM-yyyy");

            return dateDiff(dataMaiorAux, dataMenorAux, format);
        } catch (Exception e) {
            return 0;
        }
    }

    public static double calculaIdade(Date dataMenor) throws ParseException {

        // String dataMaior = formatToDDMMYYYY(getCurrentDate());
        // String dataMaior = formatToDDMMYYYY(getCurrentDate());

        try {
            //  Date dataMenorAux = createDate(dataMenor, "dd-MM-yyyy");
            //  Date dataMaiorAux = createDate(dataMaior, "dd-MM-yyyy");

            return dateDiff(getCurrentDate(), dataMenor, DateUtils.YEAR_FORMAT);
        } catch (Exception e) {
            return 0;
        }
    }


    public static String formatToDDMMYYYY(String date) throws IllegalArgumentException {
        if (date == null) return null;

        String[] partes = null;

        String dia = "",
                mes = "",
                ano = "";

        partes = date.split("/");
        if (partes.length > 1) {
            dia = partes[0];
            mes = partes[1];
            ano = partes[2];
        } else {
            partes = date.split("-");
            if (partes.length > 1) {
                dia = partes[0];
                mes = partes[1];
                ano = partes[2];
            } else {
                throw new IllegalArgumentException("O argumento indicado para o parametro Data E invalido!");
            }

        }



        /**
         * Se a data estiver no formato yyyy/mm/dd
         */
        if (Integer.parseInt(dia) > 31) {
            String anoAux = ano;
            ano = dia;
            dia = anoAux;
        }

        /**
         * Se por ventura a data estiver no formato dd/mm, isto E, se o dia vier antes do mes
         * Nota: esta situacao será detectada no caso em que o dia for maior que 12, caso contrario passará tudo despercebido.
         * 		 Isso E PERIGOSO, mas por enquanto nAo há alternativa
         */

        if (Integer.parseInt(mes) > 12){
            String mesAux = mes;

            mes = dia;
            dia = mesAux;
        }

        return dia + "-" + mes + "-" + ano;
    }

    public static String parseDateToDDMMYYYYString(Date toParse){
        SimpleDateFormat datetemp = new SimpleDateFormat("dd-MM-yyyy");
        String data = datetemp.format(toParse);
        return data;
    }

    public static String formatToDDMMYYYY_HHMISS(Date date){

        //DD-Mon-YY HH:MI:SS
        if (date == null){
            return "";
        }

        return Utilities.ensureXCaractersOnNumber(getDayOfMonth(date), 2) + "-" + Utilities.ensureXCaractersOnNumber(getMonth(date), 2) + "-" + getYear(date) + " " + formatToHHMISS(date);
    }

    /**
     * @author Jorge Boane
     * @return Transforma uma data para uma sitring no formato yyyy-mm-dd hh:mi:ss
     * @param  date
     *
     */

    public static String formatToYYYYMMDD_HHMISS(Date date){

        //DD-Mon-YY HH:MI:SS
        if (date == null){
            return "";
        }

        return formatToYYYYMMDD(date) + " " + formatToHHMISS(date);
    }

    public static String formatToYYYYMMDD(Date date){
        if (date == null) return null;

        return getYear(date) + "-" +  Utilities.ensureXCaractersOnNumber(getMonth(date), 2) + "-" + Utilities.ensureXCaractersOnNumber(getDayOfMonth(date), 2);
    }

    public static String formatToHHMISS(Date date){
        if (date == null){
            return "";
        }

        return Utilities.ensureXCaractersOnNumber(getHours(date), 2) + ":" + Utilities.ensureXCaractersOnNumber(getMinutes(date), 2) + ":" + Utilities.ensureXCaractersOnNumber(getSeconds(date), 2);
    }


    /**
     * Retorna o numero de dias entre as datas.
     *
     * @param date1 data inicial
     * @param date2 data final
     * @return numero de dias
     */
    public static long getDaysBetween(final Date date1, final Date date2)
    {
        return (date2.getTime()
                - date1.getTime())
                / (1000 * 60 * 60 * 24);
    }

    public static Date getDateFromDayAndMonthAndYear(int day,
                                                     int month,
                                                     int year)
    {
        Calendar cal = Calendar.getInstance();

        cal.set(Calendar.DAY_OF_MONTH, day);
        cal.set(Calendar.MONTH, month - 1);
        cal.set(Calendar.YEAR, year);

        return cal.getTime();
    }

    public static String getDateAfterAddingDaysToGivenDate(final String oldDate, int daysToAdd){

        SimpleDateFormat sdf = new SimpleDateFormat(DateUtils.DDMM_DATE_FORMAT);

        Calendar c = Calendar.getInstance();
        try{
            c.setTime(sdf.parse(oldDate));
        }catch(ParseException e){
            e.printStackTrace();
        }
        c.add(Calendar.DAY_OF_MONTH,daysToAdd);

        return sdf.format(c.getTime());
    }

    public static Date getDateOfPreviousDays(Date date, int days)
    {
        if (days < 0)
        {
            throw new IllegalArgumentException(
                    "The days must be a positive value");
        }

        return org.apache.commons.lang3.time.DateUtils.addDays(date, (-1) * days);
    }

    public static Date getSqlDateFromString(String stringDate, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        try {
            Date date = (Date) format.parse(stringDate);
            return date;
        } catch (ParseException e) {
            e.printStackTrace();
        }

        return null;
    }

    public static Date getUtilDateFromString(String stringDate, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        try {
            Date date = (Date) format.parse(stringDate);
            return date;
        } catch (ParseException e) {
            e.printStackTrace();
        }

        return null;
    }

    public static String getStringDateFromDate(Date date, String pattern) {
        SimpleDateFormat datetemp = new SimpleDateFormat(pattern,Locale.ROOT);
        String data = datetemp.format(date);
        return data;

    }

    public static Date getDateOfForwardDays(Date date, int days)
    {
        if (days < 0)
        {
            throw new IllegalArgumentException("The days must be a positive value");
        }

        return org.apache.commons.lang3.time.DateUtils.addDays(date, days);
    }

    public static Date setHour(Date date, int hour){
        Calendar calendar = Calendar.getInstance();

        calendar.setTime(date);

        calendar.set(Calendar.HOUR_OF_DAY, hour);

        return calendar.getTime();
    }

    public static Date setMinute(Date date, int minute){
        Calendar calendar = Calendar.getInstance();

        calendar.setTime(date);

        calendar.set(Calendar.MINUTE, minute);

        return calendar.getTime();
    }

    public static Date setSecond(Date date, int second){
        Calendar calendar = Calendar.getInstance();

        calendar.setTime(date);

        calendar.set(Calendar.SECOND, second);

        return calendar.getTime();
    }


    public static Date setTime(Date date, int hour, int minute){
        Calendar calendar = Calendar.getInstance();

        calendar.setTime(date);

        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.HOUR_OF_DAY, hour);

        return calendar.getTime();
    }

    /**
     * COloca a hora na data:
     *
     * @param date
     * @param time: horas no formato hh:mm
     * @return
     */
    public static Date setTime(Date date, String time){
        String[] timeElements = time.split(":");

        Calendar calendar = Calendar.getInstance();

        calendar.setTime(date);

        calendar.set(Calendar.MINUTE, Integer.parseInt(timeElements[1]));
        calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeElements[0]));

        if (timeElements.length > 2) calendar.set(Calendar.SECOND, Integer.parseInt(timeElements[2]));

        return calendar.getTime();
    }

    public static boolean isValidHour(int hour){
        return hour >= 0 && hour <=23;
    }

    public static boolean isValidMinute(int minute){
        return minute>= 0 && minute <=59;
    }

    /**
     *
     * @param dataMaior
     * @param dataMenor
     * @param excludedDates
     * @return
     */
    public static int countPassedDays(Date dataMaior, Date dataMenor, List<Date> excludedDates){
        List<Date> dates = generateDateListBetween(dataMenor, dataMaior);

        int qtdPaassedDays = 0;

        for (int i=0; i < dates.size();i++){
            if (!existOnList(dates.get(i), excludedDates)) qtdPaassedDays++;
        }

        /*
         * Remover o primeiro dia do periodo
         */
        qtdPaassedDays = qtdPaassedDays-1;

        return qtdPaassedDays;
    }

    private static boolean existOnList(Date date, List<Date> dayList){
        if (!Utilities.listHasElements((ArrayList<?>) dayList)) return false;

        for (Date d : dayList){
            if (compareTo(formatToDDMMYYYY(date), formatToDDMMYYYY(d)) == 0) return true;
        }

        return false;
    }

    public static int compareTo(String data1, String data2){

        long d1 = parseDateToLong(data1),
                d2 = parseDateToLong(data2);



        if (d1 == d2) return 0;
        if (d1 > d2) return 1;

        return -1;


        /*
         * parseDateToLong(validade) >= parseDateToLong(hoje): Esta comparaçAo retornará sempre
         * um valor correcto, tendo em conta que a data foi convertida para o formato 'yyyymmdd'.
         * Prova: Consulte o autor.
         *
         *
         * */


    }

    /**
     * Gera uma lista de datas
     * @param start
     * @param end
     * @return
     */
    public static List<Date> generateDateListBetween(Date start, Date end){
        int qtdDays = (int)dateDiff(end, start);

        ArrayList<Date> dates = new ArrayList<Date>();

        dates.add(start);

        for (int i=1; i < qtdDays; i++){
            dates.add(addDaysDate(dates.get(i-1), 1));
        }

        dates.add(end);

        return dates;
    }

    /**
     * Calcula a direrença entre duas datas; a diferença E em dias
     * @param dataMaior
     * @param dataMenor
     * @return
     */

    public static double dateDiff(Date dataMaior, Date dataMenor){
        double differenceMilliSeconds =  dataMaior.getTime() - dataMenor.getTime();

        return differenceMilliSeconds/1000/60/60/24;
    }

    public static long parseDateToLong(String date) throws IllegalArgumentException{
        String[] partes = null;

        String dia = "",
                mes = "",
                ano = "";

        partes = date.split("/");
        if (partes.length > 1){
            dia = partes[0];
            mes = partes[1];
            ano = partes[2];
        }
        else{
            partes = date.split("-");
            if (partes.length > 1){
                dia = partes[0];
                mes = partes[1];
                ano = partes[2];
            }
            else {
                throw new IllegalArgumentException("O argumento indicado para o parametro Data E inválido!");
            }

        }

        /**
         * Se a data estiver no formato yyyy/mm/dd
         */
        if (Integer.parseInt(dia) > 31) {
            String anoAux = ano;
            ano = dia;
            dia = anoAux;
        }

        /**
         * Se por ventura a data estiver no formato dd/mm, isto E, se o dia vier antes do mes
         * Nota: esta situacao será detectada no caso em que o dia for maior que 12, caso contrario passará tudo despercebido.
         * 		 Isso E PERIGOSO, mas por enquanto nAo há alternativa
         */

        if (Integer.parseInt(mes) > 12){
            String mesAux = mes;

            mes = dia;
            dia = mesAux;
        }

        return Long.parseLong(ano+ "" +mes+""+dia);
    }

    /**
     * Adiciona uma um perido de tempo (em dias) a uma data passada pelo parametro
     * @param dataInicial
     * @param qtdDias
     * @return Retorna a dataInicial adicionada ao tempo passado pelo parametro qtdDias
     */
    public static Date addDaysDate(Date dataInicial, int qtdDias){
        Calendar calendar = Calendar.getInstance();

        calendar.setTime(dataInicial);

        calendar.add(Calendar.DAY_OF_YEAR, qtdDias);

        return calendar.getTime();
    }

    /**
     * Adiciona uma um perido de tempo (em anos) a uma data passada pelo parametro
     * @param dataInicial
     * @param qtdAnos
     * @return Retorna a dataInicial adicionada ao tempo passado pelo parametro qtdAnos
     */
    public static Date addYearsDate(Date dataInicial, int qtdAnos){

        int ano = DateUtils.getYear(dataInicial)+qtdAnos;
        int mes = DateUtils.getMonth(dataInicial);
        int dia = DateUtils.getDayOfMonth(dataInicial);

        String newDate = dia + "-" + mes + "-" + ano;

        return createDate(newDate, DDMM_DATE_FORMAT);
    }

    public static boolean isValidAno(int ano, int minAno, int maxAno){
        return ano >= minAno && ano <= maxAno;
    }

    /**
     * Considera-se que o ano E válido se tiver 4 dígito e for maior que 0;
     * @param ano
     * @return
     */
    public static boolean isValidAno(int ano){
        String strAno = ""+ano;

        return strAno.length() == 4 && ano > 0;
    }

    /**
     * Verifica se a data recebida pelo parametro E maior que a data corrente
     * @param data
     * @return true se a data recebida for maior que a data corrente
     */
    public static boolean chechDateMaiorQueDataCorrente(Date data){
        return DateUtils.dateDiff(DateUtils.getCurrentDate(), data, DateUtils.DAY_FORMAT) < 0;
    }

    public static String castDateToString (java.sql.Date date ) {

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

        String strDate = dateFormat.format(date);

        return strDate;
    }

    public static Date addMonth(Date date, int amount){
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.add(Calendar.MONTH, amount);
        return c.getTime();
    }

    public static Date addDay(Date date, int amount){
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.add(Calendar.DATE, amount);
        return c.getTime();
    }

    public static Date setDays(Date date, int amount) {
        return set(date, Calendar.DAY_OF_MONTH, amount);
    }

    public static Date setMonth(Date date, int amount) {
        return set(date, Calendar.MONTH, amount);
    }

    private static Date set(Date date, int calendarField, int amount) {
        if (date == null) {
            throw new IllegalArgumentException("The date must not be null");
        }
        // getInstance() returns a new object, so this method is thread safe.
        Calendar c = Calendar.getInstance();
        c.setLenient(false);
        c.setTime(date);
        c.set(calendarField, amount);
        return c.getTime();
    }

    public static double getAge(Date dataMenor) throws ParseException{

        String dataMaior = DateUtils.formatToDDMMYYYY(DateUtils.getCurrentDate());

        try {
            Date dataMenorAux = DateUtils.createDate(DateUtils.formatToDDMMYYYY(dataMenor), DateUtils.DDMM_DATE_FORMAT);
            Date dataMaiorAux = DateUtils.createDate(dataMaior, DateUtils.DDMM_DATE_FORMAT);

            return DateUtils.dateDiff(dataMaiorAux, dataMenorAux, DateUtils.YEAR_FORMAT);
        } catch (Exception e) {
            return 0;
        }
    }

    public static double getAgeBetweenTwoDates(Date startDate, Date endDate) throws ParseException {
        try {
            return dateDiff(startDate, endDate, "dd-MM-yyyy");
        } catch (Exception e) {
            return 0;
        }
    }

    public static LocalDate toLocalDateSafe(Date date) {
        if (date == null) return null;

        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate();
        }

        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
