package ru.naumen.modules.gantt

/**
 * Вспомогательные функции для вычисления планируемого времени взятия в разработку / завершения разработки задач для отображения их на диаграмме Ганта
 * @author amokhov
 * @since 2019.11.02
 */

import groovy.json.JsonBuilder;
import groovy.json.JsonSlurper;
import groovy.transform.Field;
import groovy.time.TimeCategory;

//def user = utils.get('employee$1828501');
//def user = subject;

@Field NOT_CLOSED_STATES = ['wip', 'registered', 'plan', 'codeReview', 'integration', 'negotAnalytics', 'manualTesting', 'flk', 'assessment', 'negotiation', 'defectReview', 'postponed', 'toAnalyst'];

@Field IN_PROGRESS_STATES = ['wip', 'codeReview', 'integration', 'manualTesting', 'flk', 'postponed', 'toAnalyst'];

//<Профиль разработчика>
@Field def KEY_PERF = 'perf';
@Field def KEY_PROF = 'prof';
@Field def KEY_DATE = 'date';
@Field def KEY_PERF_BUG = 'bug';
@Field def KEY_PERF_DEV = 'dev';
@Field def KEY_PERF_TOTAL = 'total';
@Field def KEY_PROF_DEV = 'dev';
@Field def KEY_PROF_TOTAL = 'total';
@Field def KEY_PROF_KOEF = 'koef';
//</Профиль разработчика>

//<Обогащение классов обслуживания> ****************
//Максимальная продолжительность отпуска. Для того, чтобы обезопасить себя от случайной ошибки в дате.
@Field def MAX_VOCATION_LEN = 60; //Вряд ли реальный интервал превысит 60 дней
//</Обогащение классов обслуживания> ****************

def getDefaultServiceTime()
{
    //класс обслуживания по умолчанию (40 часов, с 8:00 до 17:00)
    return utils.get('servicetime$127101');
}

def tasks = getNotClosedUserTasks(user);
tasks.each{ t ->
    logger.info(t.getTitle());
}

def getNotClosedUserTasks(def user)
{
    model = [:]
    model.put('state', NOT_CLOSED_STATES)
    model.put('respDevelop', user)
    return utils.find('smrmTask$devTask', model);

    /*
      def getNotClosedUserTasksQuery = 'from smrmTask$devTask where ' +
        ' state not in :states' +
        ' and respDevelop = :dev';
      def query = api.db.query(getNotClosedUserTasksQuery);
      query.set("states", NOT_CLOSED_STATES);
      query.set("dev", user);
      return query.list();
    */
}

/**
 * Устанавливает дату взятия в работу для всех взятых в работу задач, если она не установлена
 */
def setDevStartIfNeeded(def notClosedTasks)
{
    notClosedTasks?.each{ task ->
        if(IN_PROGRESS_STATES.contains(task.state) && task.devStart == null)
        {
            def states = utils.stateHistory(task);
            def devStart = states?.find{ "plan".equals(it.stateCode) && "wip".equals(it.newStateCode)}?.eventDate;
            if(devStart != null)
            {
                try
                {
                    //api.tx.call {
                    task = utils.edit( task, ['devStart' : devStart]);
                    //}
                    logger.info("devStart updated: " + devStart + ", task: " + task.UUID);
                }
                catch(Exception e)
                {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }
}

/**
 * Рассчитывает и устанавливает скорректированную оценку разработки.
 * Пока не определен параметр "Оценка оставшегося времени разработки" равен "Оценке разработки",
 * иначе рассчитывается следующим образом: "Оценка оставшегося времени разработки" + "Суммарное время ТЗТ, списанное ответственным разработчиком"
 */
def calcCorrectedDevEstimation(def notClosedTasks)
{
    notClosedTasks?.each{ task ->
        def correctedDevEstimation = task.devTime;
        if(task.remainDev != null)
        {
            def tztInMinutes = (int)(getTztOnTaskByUserInHours(task, task.respDevelop)*60);
            logger.info("ТЗТ ответственного разработчика, min: " + tztInMinutes + ", task.remainDev: " + task.remainDev + ", task: " + task.UUID);
            correctedDevEstimation = task.remainDev + tztInMinutes;
        }
        if(correctedDevEstimation != null && task.corDevEst != correctedDevEstimation)
        {
            try
            {
                //api.tx.call {
                task = utils.edit( task, ['corDevEst' : correctedDevEstimation, 'corDevEstStr' : modules.smrmTaskUtils.getTZTAsString((int)correctedDevEstimation)]);
                //}
                logger.info("correctedDevEstimation updated: " + correctedDevEstimation + ", task: " + task.UUID);
            }
            catch(Exception e)
            {
                logger.error(e.getMessage(), e);
            }
        }
    }
}

def filterInProgress(def notClosedTasks)
{
    return notClosedTasks == null ? [] : notClosedTasks.findAll{ it.devStart != null };
}

def filterPlanned(def notClosedTasks)
{
    def result = notClosedTasks == null ? [] : notClosedTasks.findAll{ it.devStart == null };
    return result?.sort { a, b -> a.rank == null ? 1 : ( b.rank == null ? -1 : (a.rank - b.rank))};
}

/**
 * Оценка разработки задачи в часах
 */
def getTaskEstDevTimeInHours(def task)
{
    //Вместо devTime берем corDevEst
    //return (task == null || task.devTime == null) ? 0 : (int)(task.devTime/60);
    logger.info('getTaskEstDevTimeInHours: ' + task?.corDevEst + ", task: " + task.UUID);
    return (task == null || task.corDevEst == null) ? 0 : (int)(task.corDevEst/60);
}

/**
 * Количество списанных часов сотрудником на задачу
 */
def getTztOnTaskByUserInHours(def task, def user)
{
    def tzt = api.db.query('select sum(ReportTime) from report where smrmTask.id = :s and author.id = :a').set('s', task).set('a', user).list();
    return (tzt==null || tzt.isEmpty()) ? 0 : (tzt.get(0) == null ? 0 : tzt.get(0));
}

/**
 * Получить время обслуживания (соответствующее классу обслуживания) для текущего момента времени.
 * Пример: для класса обслуживания "40 часов в неделю" время обслуживания с 9:00 до 17:00, то есть,
 * если вызвать ф-ю 02.11.2019 13:45 (сб.) должен вернуть 05.11.2019 09:00 (вт), т.к. 04.11.2019 (пн) - выходной
 *
 * @param user - сотрудник, для которого вычисляется время обслуживания.
 */
def getServiceTimeForCurrentDate(def user)
{
    def serviceTime = getUserServiceTime(user);
    return api.timing.addWorkingHours(null, 0, serviceTime, user?.timeZone);
}

def getUserServiceTime(def user)
{
    //return user?.timeWork == null ? getDefaultServiceTime() : user.timeWork;
    return getDevPersonalSt(user);
}

/**
 * Получить колличество миллисекунд, прошедших с момента начала разработки задачи (смена статуса plan->wip) по текщий момент в соответствии с
 * классом обслуживания. Пример:
 *  - переход в wip: 01.11.2019 16:14 (пт);
 *  - класс обслуживания: "40 часов в неделю" (с 9:00 до 17:00)
 *  - вызываем ф-ю: 04.11.2019 20:35 (пн., но выходной);
 *  - результат: 2760000 (т.е. 46 минут)
 */
def getTaskServiceTimeMills(def task, def user)
{
    return task.devStart == null ? 0 : api.timing.serviceTime(getUserServiceTime(user), user.timeZone, task.devStart, getServiceTimeForCurrentDate(user));
}

/**
 * Получить ожидаемое время завершения разработки для задачи с учетом фактически списанных ТЗТ ответственным разработчиком
 * Пример:
 * Время начала разработки: 01.11.2019 16:14 (пт)
 * Оценка разработки: 3d
 * Списано ТЗТ (отв. разработчик): 6h 30m
 * Время вызова ф-и: 04.11.2019 21:05 (выходной)
 * Ожидаемый результат без учета ТЗТ: 07.11.2019 16:14
 * Ожидаемый результат c учетом ТЗТ: 07.11.2019 11:14 (т.к. разница м/д ожидаемым временем списания (46 мин.) и фактическим (6,5 ч.) = -5,733 часа, а при добавлении времени учитываем только целую часть, то есть (-5))
 *
 * @param task - задача, для которой определяем ожидаемое время завершения разработки
 * @param expDevStart - ожидаемая дата взятия в работу (используется, если дата взятия в работу не определена в задаче)
 */
def getExpectedEndDate(def task, def expDevStart)
{
    //ответственный разработчик
    def user = task.respDevelop;
    def startDate = task.devStart == null ? expDevStart : task.devStart;
    //коэффициент производительности разработчика для данного типа задачи
    def koef = getDevPerfKoefByTask(user, task);
    //Изначальное планируемое время завершения разработки: дата начала разработки + оценка разработки с учетом класса обслуживания
    //и коэффициента производительности
    def plannedDate = api.timing.addWorkingHours(startDate, (int)(getTaskEstDevTimeInHours(task) * koef), getUserServiceTime(user), user.timeZone);
    //Разница между ожидаемым временем по классу обслуживания, прошедшим с начала разработки и списанными ТЗТ на задачу (мс)
    //Здесь коэффициент производительности не учитываем, так как мы его учли выше при рассчете изначально планируемого времени завершения разработки
    def progressDeltaMs = getTaskServiceTimeMills(task, user) - (getTztOnTaskByUserInHours(task, user) * 60 * 60 * 1000);
    //Добавляем к изначально-запланированной дате завершения разницу между полным временем обслуживания и фактическими ТЗТ (в часах).
    //Если списано больше ТЗТ (например, работал в выходные), то ожидаемое время завершения разработки уменьшится
    def expectedEndDate = api.timing.addWorkingHours(plannedDate, (int)(progressDeltaMs/1000/60/60), getUserServiceTime(user), user.timeZone);
    //Ожидаемое время завершения работ не должно быть меньше даты начала работ (такое возможно при превышении ТЗТ)
    expectedEndDate = (expectedEndDate.compareTo(startDate) < 0) ? startDate : expectedEndDate;
    //Значения в прошлом тоже не возвращаем
    return (expectedEndDate.compareTo(new Date()) < 0) ? getServiceTimeForCurrentDate(user) : expectedEndDate;
}

/**
 * Рассчитать и установить ожидаемые даты начала разработки и завершения разработки для незакрытых задач сотрудника
 * @param user - сотрудник, по задачам которого рассчитываем ожидаемые даты начала разработки и завершения разработки
 */
def calcExpectedDatesForEmplTasks(def user)
{
    calcExpectedDatesForTasks(getNotClosedUserTasks(user));
}

/**
 * Рассчитать и установить ожидаемые даты начала разработки и завершения разработки для задач
 * @param tasks - задачи, для которых рассчитываем ожидаемые даты начала разработки и завершения разработки
 * Важно: предполагается, что все переданные задачи имеют одного и того же ответственного разработчика.
 */
def calcExpectedDatesForTasks(def tasks)
{
    if(!tasks || !(tasks[0].respDevelop))
    {
        return;
    }
    def user = tasks[0].respDevelop;

    setDevStartIfNeeded(tasks);
    calcCorrectedDevEstimation(tasks);
    def inProgress = filterInProgress(tasks);
    def maxExpEndDate = null;
    //Устанавливаем ожидаемую дату завершения разработки у задач, находящихся в работе
    inProgress?.each{ task ->
        task = utils.get(task.UUID);
        try
        {
            //api.tx.call {
            task = utils.edit( task, ['expDevStart' : task.devStart, 'expDevEnd' : getExpectedEndDate(task, null)]);
            //}
        }
        catch(Exception e)
        {
            logger.error(e.getMessage(), e);
        }
        //logger.error("^^^^^^^ in progress: " + task.getTitle() + " : expEndDate: " + task.expDevEnd);
        if( maxExpEndDate == null || maxExpEndDate.compareTo(task.expDevEnd) < 0 )
        {
            maxExpEndDate = task.expDevEnd;
        }
    }

    def currentServiceTime = getServiceTimeForCurrentDate(user);
    //Ожидаемая дата начала разработки первой задачи, находящейся в плане (не взятой в работу)
    def expStartDate = (maxExpEndDate == null || maxExpEndDate.compareTo(currentServiceTime) < 0) ? currentServiceTime : maxExpEndDate;

    def planned = filterPlanned(tasks);
    //Устанавливаем ожидаемую дату начала и завершения разработки у задач, находящихся в плане (они будут планироваться последовательно)
    planned?.each{ task ->
        task = utils.get(task.UUID);
        try
        {
            //api.tx.call {
            task = utils.edit( task, ['expDevStart' : expStartDate, 'expDevEnd' : getExpectedEndDate(task, expStartDate)]);
            //}
        }
        catch(Exception e)
        {
            logger.error(e.getMessage(), e);
        }
        //logger.error("^^^^^^^ planned: " + task.getTitle() + " : expDevStart: " + task.expDevStart + "; expDevEnd: " + task.expDevEnd);
        if(task?.expDevEnd)
        {
            expStartDate = task.expDevEnd;
        }
    }
}

/**
 * Получить всех сотрудников подразделения вместе с руководителями рекурсивно, включая вложенные подразделения
 * @param ou - подразделение вернхнего уровня, для которого требуется получить вложенных сотрудников
 *
 */
def listOuEmpls(def ou)
{
    def empls = ou.employees == null ? [] : new ArrayList<>(ou.employees);
    if(ou.head != null)
    {
        empls.add(ou.head);
    }
    //"incState" - Список вложенных подразделений
    if(ou.incState != null)
    {
        ou.incState.each
                {
                    empls.addAll(listOuEmpls(it));
                }
    }
    return empls;
}

/**
 * Получить всех разработчиков подразделения вместе с руководителями рекурсивно, включая вложенные подразделения
 * @param ou - подразделение вернхнего уровня, для которого требуется получить вложенных разработчиков
 *
 */
def listOuDevs(def ou)
{
    def empls = listOuEmpls(ou);
    def mtTeam = getMtTeam();
    return empls.findAll{ !it?.teams?.contains(mtTeam)} ;
}

def getMtTeam()
{
    //Группа "Ручное тестирование"
    return utils.get('team$2453501');
}

/**
 * Рассчитать и установить ожидаемые даты начала разработки и завершения разработки для незакрытых задач сотрудников
 * группы, включая руководителя
 * @param ou - подразделение, для сотрудников которого необходимо рассчитать ожидаемые даты начала разработки и завершения разработки по всем задачам,
 * где они являются ответственными разработчиками
 */
def calcExpectedDatesForEmplsInGroup(def ou)
{
    def empls = listOuEmpls(ou);
    empls.each{ empl ->
        //logger.error('***** ' + empl.getTitle());
        calcExpectedDatesForEmplTasks(empl);
    }
}

/**
 * Рассчитать и установить ожидаемые даты начала разработки и завершения разработки для незакрытых задач разработчиков команды
 * @param team - команда, для участников (разработчиков) которой необходимо рассчитать ожидаемые даты начала разработки и завершения разработки по всем задачам,
 * где они являются ответственными разработчиками
 */
def calcExpectedDatesForDevsInTeam(def team)
{
    def empls = (team?.members == null) ? [] : team.members;
    def mtTeam = getMtTeam();
    def devs = empls.findAll{ !it?.teams?.contains(mtTeam)} ;
    devs.each{ dev ->
        calcExpectedDatesForEmplTasks(dev);
    }
}

// *************** <Производительность разработчиков> ***************
/**
 * Рассчитывает коэффициенты производительности разработчика по закрытым задачам на разработку (devTask),
 * в которых он является ответственным разработчиком и возвращает их в виде мапы с ключами:
 * <li>'bug' - производительность по дефектам</li>
 * <li>'dev' - производительность по НЕдефектам (то есть задачам на разработку и работам)</li>
 * <li>'total' - суммарная производительность</li>
 *
 * Каждый коэффициент есть отношение оценочного времени разработки к списанному данным разработчиком времени
 *
 * @param employee - сотрудник (разработчик), для которого рассчитывается производительность
 * @param toDate - дата окончания периода, по которому рассчитывается статистика
 * @param monthCount - количество месяцев, отнимаемых от {@code toDate} для вычисления даты начала периода статистики
 * @return мапа с ключами 'bug', 'dev', 'total' и вещественным коэффициентом в качестве значения (округлен до 2-х
 * знаков полсле запятой)
 */
def calcDevPerformance(def employee, def toDate = new Date(), def monthCount = 6)
{
    //def empl = utils.get('employee$79380123');
    //def toDate = Date.parse("yyyy-MM-dd","2020-05-30");
    if(!isDeveloper(employee))
    {
        return [:];
    }

    // КОНСТАНТЫ
    // Индексы в результирующем наборе
    def CASE_INDX = 2, EST_INDX = 3, LOG_INDX = 4;
    // Код типа "Дефект"
    def BUG_CASE_ID = 'bug';

    // ОСНОВНОЙ БЛОК
    def fromDate;
    use(TimeCategory)
            {
                fromDate = toDate - monthCount.month
            }

    def qr = api.db.query(
            '''select 
	            smrmTask.title, smrmTask.id, smrmTask.metaCaseId, smrmTask.devTime/60 as est, sum(ReportTime) as loggedDev 
	        from report tzt
            where 
                smrmTask.id in (
                    select smrmTask.id
                    from report
                    where
                    dateReort between :from and :to
                    and state <> 'deny'
                    and author.id = :author
                    and smrmTask.state = 'closed'
                    and smrmTask.respDevelop = author
                    group by smrmTask.id
                    )
	            and author.id = :author
            group by smrmTask.title, smrmTask.id, smrmTask.metaCaseId, smrmTask.devTime''').set(
                'from', fromDate).set('to', toDate).set('author', employee).list();

    logger.info('Employee : ' + employee?.title);

    def totalEst = 0, totalLogged = 0, totalEstBug = 0, totalLoggedBug = 0;

    qr.each {
        totalEst += it[EST_INDX] ? it[EST_INDX] : 0;
        totalLogged += it[LOG_INDX] ? it[LOG_INDX] : 0;
        if (BUG_CASE_ID == it[CASE_INDX]) {
            totalEstBug += it[EST_INDX] ? it[EST_INDX] : 0;
            totalLoggedBug += it[LOG_INDX] ? it[LOG_INDX] : 0;
        }
        logger.info(it.join(" : "));
    }

    def totalEstDevWrk = totalEst - totalEstBug;
    def totalLoggedDevWrk = totalLogged - totalLoggedBug;

    logger.info('totalEst : ' + totalEst + '; totalLogged : ' + totalLogged);
    logger.info('totalEstBug : ' + totalEstBug + '; totalLoggedBug : ' + totalLoggedBug);
    logger.info('totalEstDevWrk : ' + totalEstDevWrk + '; totalLoggedDevWrk : ' + totalLoggedDevWrk);

    def result = [:]
    result[KEY_PERF_BUG] = totalEstBug == 0 ? 0 : Math.round(totalLoggedBug / totalEstBug * 100) / 100;
    result[KEY_PERF_DEV] = totalEstDevWrk == 0 ? 0 : Math.round(totalLoggedDevWrk / totalEstDevWrk * 100) / 100;
    result[KEY_PERF_TOTAL] = totalEst == 0 ? 0 : Math.round(totalLogged / totalEst * 100) / 100;

    return result
}

/**
 * Является ли сотрудник разработчиком?
 *
 * @param employee - сотрудник
 * @return true, если является, иначе false
 */
def isDeveloper(def employee)
{
    def team = utils.get('team$84663101'); //Команда "Релизная разработка"
    def excludedDevList = ['employee$2317103']; //Мохов
    return team?.members?.contains(employee) && !excludedDevList.contains(employee?.UUID);
}

/**
 * Создает "Профиль разработчика", который имеет формат:
 * {
 *  "perf":
 *      {
 *          "bug": 1.15,
 *          "dev": 1.36,
 *          "total": 1.26
 *      },
 *  "prof":
 *      {
 *          "dev": 150.92,
 *          "total": 176.01,
 *          "koef": 0.86
 *      },
 *  "date": "05.02.2021"
 * }
 * @param employee - разработчик, профиль которого требуется создать
 * @return мапа в формате, описанном выше
 */
def createDevProfile(def employee, def toDate = new Date(), def monthCount = 6)
{
    def result = [:];
    result[KEY_PERF] = calcDevPerformance(employee, toDate, monthCount);
    result[KEY_PROF] = calcDevTimeProfile(employee, toDate, monthCount);
    result[KEY_DATE] = new Date().format('dd.MM.yyyy' );
    return result;
}

def toJsonString(def object)
{
    return object ? new JsonBuilder(object).toPrettyString() : "";
}

def parseObject(def jsonString)
{
    def parser = new JsonSlurper()
    try
    {
        return parser.parseText(jsonString);
    }
    catch(Exception e)
    {
        return null;
    }
}

def getPerfKey(def jsonString, def key)
{
    def map = parseObject(jsonString);
    return map ? map[KEY_PERF][key] : 1;
}

def getPerfTotal(def jsonString)
{
    return getPerfKey(jsonString, KEY_PERF_TOTAL);}

def getPerfBug(def jsonString)
{
    return getPerfKey(jsonString, KEY_PERF_BUG);
}

def getPerfDev(def jsonString)
{
    return getPerfKey(jsonString, KEY_PERF_DEV);
}

/**
 * Возвращает коэффициент производительности разработчика в зависимости от типа задачи
 * @param employee - разработчик
 * @param task - задача
 * @return коэффициент производительности разработчика для данного типа задач.
 */
def getDevPerfKoefByTask(def employee, def task)
{
    def jsonProfile = employee?.devProfile;
    return 'bug' == task?.metaClass.code ? getPerfBug(jsonProfile) : getPerfDev(jsonProfile);
}

/**
 * Рассчитывает коэффициент ТЗТ на разработку (“prof”), то есть отношение ТЗТ, списанных на задачи разработки
 * (devTask) в которых данный сотрудник является ответственным разработчиком, к общему объему списанных ТЗТ
 * за некоторый период времени (например, полгода) и возвращает их в виде мапы с ключами:
 * <li>'dev' - трудозатраты (в часах), списанные на задачи разработки (devTask), в которых данный сотрудник
 *  является ответственным разработчиком</li>
 * <li>'total' - общий объем списанных ТЗТ</li>
 * <li>'koef' - отношение ТЗТ на разработку к общему (округлен до 2-х знаков полсле запятой)</li>
 *
 * @param employee - сотрудник (разработчик), для которого рассчитывается профиль
 * @param toDate - дата окончания периода, по которому рассчитывается статистика
 * @param monthCount - количество месяцев, отнимаемых от {@code toDate} для вычисления даты начала периода статистики
 * @return мапа с ключами 'dev', 'total', 'koef' и вещественным коэффициентом в качестве значения
 */
def calcDevTimeProfile(def employee, def toDate = new Date(), def monthCount = 6)
{
    //def empl = utils.get('employee$1828501'); //Меркульев
    if(!isDeveloper(employee))
    {
        return [:];
    }

    // КОНСТАНТЫ
    // Индексы в результирующем наборе
    def DEV_INDX = 0, TOTAL_INDX = 1;

    // ОСНОВНОЙ БЛОК
    def fromDate;
    use(TimeCategory)
    {
        fromDate = toDate - monthCount.month
    }

    def queryString =
            '''select sum(ReportTime) as dev, (
	            select sum(ReportTime)
		            from report
		            where
			            dateReort between :from and :to
			            and state <> 'deny'
			        and author.id = :author
		        ) as total
	            from report
                where 
                    dateReort between :from and :to
                    and state <> 'deny'
                    and author.id = :author
                    and smrmTask.respDevelop = author
                    ''';

    def qr = api.db.query(queryString).set('from', fromDate).set('to', toDate).set('author', employee).list().get(0);
    def dev = qr[DEV_INDX] ? (Math.round(qr[DEV_INDX] * 100) / 100) : 0;
    def total = qr[TOTAL_INDX] ? (Math.round(qr[TOTAL_INDX] * 100) / 100) : 0;
    def koef = total ? (Math.round(dev / total * 100) / 100) : 0;
    logger.info('Employee : ' + employee?.title + ' : dev : ' + dev + '; total : ' + total + '; koef : ' + koef);
    def result = [:]
    result[KEY_PROF_DEV] = dev;
    result[KEY_PROF_TOTAL] = total;
    result[KEY_PROF_KOEF] = koef;
    return result;
}

def getProfKoef(def jsonString)
{
    def map = parseObject(jsonString);
    return map ? map[KEY_PROF][KEY_PROF_KOEF] : 0;
}

def createTeamDevProfile(def team, def toDate = new Date(), def monthCount = 6)
{
    //def team = utils.get('team$59456401'); // Релизная.ЕКБ
    //def toDate = Date.parse("yyyy-MM-dd","2020-05-01");
    //def monthCount = 2;
    def teamDevProf;
    //ВНИМАНИЕ! Просто делить все результаты на кол-во разработчиков нельзя, так как
    //если разработчик, например, не делал дефекты, у него в производительности стоит bug = 0
    //и следовательно это уменьшит коэффициент. Поэтому нужно не учитывать нули в параметрах
    //производительности разработчика и делить каждый показатель на количество разработчиков,
    //у которых в данном показателе не 0.
    def devCnt = 0, bugCnt = 0, totalCnt = 0;

    team?.members?.each
            {
                if(modules?.gantt?.isDeveloper(it))
                {
                    def devProf = modules?.gantt?.createDevProfile(it, toDate, monthCount);
                    logger.info("rrr " +it.title + ":"+ modules?.gantt?.toJsonString(devProf));
                    if(devProf[KEY_PERF][KEY_PERF_BUG] != 0) { bugCnt++; }
                    if(devProf[KEY_PERF][KEY_PERF_DEV] != 0) { devCnt++; }
                    if(devProf[KEY_PERF][KEY_PERF_TOTAL] != 0) { totalCnt++; }
                    if(!teamDevProf)
                    {
                        teamDevProf = devProf.clone();
                    }
                    else
                    {
                        teamDevProf.each{ k, v ->
                            if( !KEY_DATE.equals(k) )
                            {
                                v.each{ k2, v2 ->
                                    logger.info(it.title + " : " + k + " " + k2 + " : " + v2)
                                    teamDevProf[k][k2] += devProf[k][k2];
                                }
                            }
                        }
                    }
                }
            }
    if(teamDevProf)
    {
        if(bugCnt != 0)
        {
            teamDevProf[KEY_PERF][KEY_PERF_BUG] = Math.round(teamDevProf[KEY_PERF][KEY_PERF_BUG] / bugCnt * 100) / 100;
        }
        if(devCnt != 0)
        {
            teamDevProf[KEY_PERF][KEY_PERF_DEV] = Math.round(teamDevProf[KEY_PERF][KEY_PERF_DEV] / devCnt * 100) / 100;
        }
        if(totalCnt != 0)
        {
            teamDevProf[KEY_PERF][KEY_PERF_TOTAL] = Math.round(teamDevProf[KEY_PERF][KEY_PERF_TOTAL] / totalCnt * 100) / 100;
        }
        if(teamDevProf[KEY_PROF][KEY_PROF_TOTAL] != 0)
        {
            teamDevProf[KEY_PROF][KEY_PROF_KOEF] = Math.round(teamDevProf[KEY_PROF][KEY_PROF_DEV] / teamDevProf[KEY_PROF][KEY_PROF_TOTAL] * 100) / 100;
        }
    }

    return teamDevProf

}
// *************** </Производительность разработчиков> ***************
// *************** <Обогащение классов обслуживания> *****************

/**
 * Возвращает список будущих неотмененных отпусков/отгулов/болезней сотрудника
 * @param employee
 */
def listPlannedVocations(def employee)
{
    //def empl = utils.get('employee$2162702'); //Борисов
    def currentDate = new Date();
    def EXCL_VOC_STATES = ['cancel']; //Исключаемые из выборки статусы отпусков
    //Отпуск.Сотрудник уходящий в отпуск (empl) <--> Сотрудник Naumen.Отпуска сотрудника (vocations)
    return employee?.vocations.findAll{ it.dateTo > currentDate && !EXCL_VOC_STATES.contains(it.state)};
}

def printPeriods(def st)
{
    logger.info('Periods for ST: ' + st?.title + ' (' + st?.UUID + ')');
    st?.periods?.each {
        def stime = it.getStartTime()/(1000*60*60);
        def et = it.getEndTime()/(1000*60*60);
        logger.info(it.UUID + ': ' + it.getDayOfLocalWeek() + ": " + it.getStartTime() + ": " + it.getEndTime() + " (" + stime + ": " + et + ")");
    }
}

def printExclusions(def st)
{
    logger.info('Exclusions for ST: ' + st?.title + ' (' + st?.UUID + ')');
    st?.exclusions?.each {
        logger.info(it.UUID + ': ' + it.getExclusionDate());
    }
}

/**
 * Является ли переданный объект персональным классом обслуживания
 * (см.: https://naupp.naumen.ru/sd/operator/#uuid:smrmTask$111379433)
 * @param st - класс обслуживания для проверки
 * @return true - КОКС, false - иначе
 */
def isPersonalSt(def st)
{
    return st?.parent && st?.employee;
}

/**
 * Обновляет периоды обслуживания для персонального класса обслуживания (КОКС) в соответствии с коэффициентом,
 * определяющим, какой процент времени разработчик тратит на задачи разработки, в которых он является
 * ответственным разработчиком (см.: https://naupp.naumen.ru/sd/operator/#uuid:changeRecords$114914502).
 * @param st - пресональный класс обслуживания
 * @param koef - коэффициент, определяющий процент времени, который разработчик тратит на задачи разработки,
 * в которых он является ответственным разработчиком (вещественное число от 0 до 1)
 */
def updatePersonalStPeriods(def st, def koef)
{
    //Только для КОКСов:
    if(!isPersonalSt(st))
    {
        return;
    }
    def sourceSt = st.parent;
    def periods = st.periods;
    for(int i = 0; i < sourceSt?.periods?.size(); i++)
    {
        def koksPeriod = periods?.get(i);
        def sourceStPeriod = sourceSt?.periods?.get(i);
        koksPeriod.startTime = sourceStPeriod.startTime;
        koksPeriod.endTime = sourceStPeriod.startTime + ((sourceStPeriod.endTime - sourceStPeriod.startTime) * koef);
    }
    api.utils.edit(st, ['periods' : periods]);
}

/**
 * Обогащает персональный класс сотрудника информацией о планируемых отпусках, отгулах, болезнях и т.д. с
 * целью учета этой информации при планировании ресурсов разработки
 * @param st - персональный класс обслуживания сотрудника
 */
def updatePersonalStExclusions(def st)
{
    //Только для КОКСов:
    if(isPersonalSt(st))
    {
        def voc = listPlannedVocations(st?.employee);
        def currentDate = new Date();
        voc?.each{
            def exclDate = (it.dateFrom > currentDate) ? it.dateFrom : currentDate;
            long interval = (it.dateTo.getTime() - exclDate.getTime())/(1000*60*60*24);
            logger.info('Interval: ' + interval + ' days')
            if(interval >=0 && interval < MAX_VOCATION_LEN)
            {
                while(exclDate <= it.dateTo)
                {
                    logger.info('Creating exclusion for st ' + st.UUID + ' on date ' + exclDate.toString());
                    //см.: https://naupp.naumen.ru/sd/operator/#uuid:domesticsup$116364439,
                    def startTime = 0;
                    def endTime = 86400000 - 1;//24 часа
                    //После исправления дефекта https://naupp.naumen.ru/sd/operator/#uuid:smrmTask$92708503
                    //переписать на создание исключений без периодов
                    try {
                        api.serviceTime.createExclusionApproved(st.UUID, exclDate, startTime, endTime);
                    }
                    catch(Exception e)
                    {
                        logger.error(e.getMessage());
                    }
                    exclDate = exclDate.plus(1);
                }
            }
        }
    }
}

/**
 * Возвращает (при необходимоси создает) персональный класс обслуживания для разработчика, обогащая его
 * инофрмацией об отпусках/отгулах/болезнях, а также корректируя время обслуживания в соответствии с
 * профилем разработки
 * @param employee - сотрудник (разработчик), для которого требуется получить персонвльный класс обслуживания
 * @param needUpdate - требуется ли обновлять информацию о периодах обслуживания и исключениях в случае, если
 * персональный класс обслуживания уже создан
 * @return персональный класс обслуживания разработчика либо null, если переданный сотрудник не разработчик
 */
def getDevPersonalSt(def employee, def needUpdate = false)
{
    if(!isDeveloper(employee))
    {
        logger.error("Can't get Personal Service Time for employee " + employee?.title +
                ": user is not developer");
        return null;
    }
    def personalSt = api.serviceTime.getPersonalServiceTime(employee);
    def justCreated = false;
    if(!personalSt)
    {
        def st = employee?.timeWork == null ? getDefaultServiceTime() : employee.timeWork;
        personalSt = api.serviceTime.createPersonalServiceTime(st, employee, true);
        justCreated = true;
    }
    if(justCreated || needUpdate)
    {
        def koef = getProfKoef(employee?.devProfile);
        if(koef == 0)
        {
            logger.info("Developer profile for employee " + employee?.title + " is incorrect. Trying to recreate");
            //api.tx.call {
            utils.edit( employee, ['devProfile' : toJsonString(createDevProfile(employee))]);
            //}
            koef = getProfKoef(employee?.devProfile);
            if(koef == 0)
            {
                utils.throwReadableException("Can't create developer profile for employee " + employee?.title);
            }
        }
        updatePersonalStPeriods(personalSt, koef);
        updatePersonalStExclusions(personalSt);
    }
    return personalSt;
}

// *************** </Обогащение классов обслуживания> ****************