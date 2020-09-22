package ru.naumen.modules.gantt.timing;

import javax.annotation.Nullable;
import groovy.transform.Field;
import ru.naumen.core.shared.IUUIDIdentifiable;
import ru.naumen.core.server.catalog.servicetime.ServiceTimeCatalogItem;

@Field
def prefixObjectLoaderService = api.timing.prefixObjectLoaderService;
@Field
def serviceTimeUtils = api.timing.serviceTimeUtils;

/**
 * Получение регламентного периода обслуживания, вычисленного по дате окончания периодa обслуживания
 * Важно! Позволяет работать непосредственно с объектами класса ServiceTimeCatalogItem без переподнятия
 * из БД (что удобно для формирования serviceTime "на лету")
 *
 * @param serviceTime элемент справочника "Классы обслуживания"
 * @param timeZone элемент справочника "Часовые пояса"
 * @param startTime время от которого необходимо начать отсчитывать период обслуживания (Date)
 * @param endTime датa окончания периода обслуживания
 * @return регламентный период обслуживания
 */
public long serviceTime(IUUIDIdentifiable serviceTime, IUUIDIdentifiable timeZone, Date startTime, Date endTime)
{
    if(serviceTime instanceof ServiceTimeCatalogItem)
    {
        def tz = prefixObjectLoaderService.get(timeZone.getUUID());
        def calculator = serviceTimeUtils.getCalculator(tz.getTimeZone(), serviceTime);
        calculator.setStartDate(startTime);
        calculator.setEndDate(endTime);
        return calculator.getServiceTime();
    }
    return api.timing.serviceTime(serviceTime, timeZone, startTime, endTime);
}

/**
 * Добавить/отнять рабочие часы
 * Важно! Позволяет работать непосредственно с объектами класса ServiceTimeCatalogItem без переподнятия
 * из БД (что удобно для формирования serviceTime "на лету")
 *
 * @param date дата-время. Если передан null, то пересчет применяется к текущей дате
 * @param hours количество добавляемых/отнимаемых(в случае отрицательного amountOfHours) рабочих часов
 * @param serviceTime элемент справочника "Классы обслуживания"
 * @param timeZone элемент справочника "Часовые пояса". Если в параметре null, берется часовый пояс сервера
 * @return Пересчитанное значение даты-времени
 */
public Date addWorkingHours(@Nullable Date date, int amountOfHours, IUUIDIdentifiable serviceTime,
                            @Nullable IUUIDIdentifiable timeZone)
{
    if(serviceTime instanceof ServiceTimeCatalogItem)
    {
        def tz = api.timing.getTimeZoneIfNotNull(timeZone);
        def dateOperationHelper = serviceTimeUtils.getDateOperationHelper(tz, serviceTime);
        dateOperationHelper.setServiceTime(serviceTime);
        dateOperationHelper.setTimeZone(tz);
        return dateOperationHelper.addWorkingHours(date, amountOfHours);
    }
    return api.timing.addWorkingHours(date, amountOfHours, serviceTime, timeZone);
}