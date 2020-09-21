package ru.naumen.modules.release;

/**
 * Модуль, содержащий полезные функции для управления релизом
 * 
 * @author amokhov
 * @since 2020.03.31
 */

 /**
  * Заменяет атрибут "Команда" агрегирующего атрибута "Ответственный" для всех объектов метакласса
  * указанного сотрудника (атрибут "Содтрудник" агрегирующего атрибута "Ответственный")
  * Функция полезна при исключении сотрудника из группы в случае, если он является ответственным 
  * в рамках команды за какие-то объекты.
  * 
  * @param emp Сотрудник для объектов в ответственности которого меняем команду, например 'employee$1778'
  * @param srcTeam Команда, которую нужно заменить, например 'team$1987402'
  * @param dstTeam Команда, на которую нужно заменить, например null
  * @param fqn fqn метакласса, по объектам которого ищем, например 'codereviewer' 
  * @param showOnly нужно ли только вывести UUID объектов (true) или реально изменить команду (false)
  */
 def changeResponsibleTeam(def emp, def srcTeam, def dstTeam, def fqn, def showOnly = true)
 {
	 /* Для вызова без модуля:
	 def srcTeam = 'team$1987402';//BPM, откуда удалить
	 def dstTeam = null;
	 def empl = 'employee$1778'; //Безруков
	 def fqn = 'codereviewer'; //Цензор
	 def showOnly = true;
	 */
	 
	 objs = utils.find(fqn, ['responsibleEmployee' : empl, 'responsibleTeam' : srcTeam]);
	 result = '';
	 objs.each{
	   result += it.UUID + '<br/>';
	   if(!showOnly)
	   {
		 utils.edit(it, ['responsibleTeam' : dstTeam]);
	   }
	 }
	 return result;
 }

/**
 * Возвращает коллекцию актуальных интеграций в заданную версию.
 * Под 'актуальными' подразумеваются еще непрошедшие (useErrorStates = false) или в том числе прошедшие с
 * ошибкой (useErrorStates = true) интеграции в незакрытые задачи.
 *
 * @param targetVersion - версия, интеграции в которую ищем
 * @param useErrorStates - возвращать или нет интеграции со статусами 'manageError' и 'testingError' (иногда может
 * понадобиться, так как они могут быть перезапущены)
 * @return коллекция актуальных интеграций
 */
def listActualIntegrations(def targetVersion, def useErrorStates = false)
{
	//Все возможные варианты: ['inprogress', 'queued', 'registered', 'aborted', 'closed', 'manageError', 'testingError']
	def INTEGRATION_ACTUAL_CODES = ['inprogress', 'queued', 'registered'];
	if(useErrorStates)
	{
		INTEGRATION_ACTUAL_CODES += ['manageError', 'testingError'];
	}
	def integrations = utils.find('integration', ['versionLink' : targetVersion, 'state' : INTEGRATION_ACTUAL_CODES ]);
	return integrations.findAll{ it.issue?.state != 'closed' };
}

// *************** <Отцепление релизной ветки (rc)> ***************

/**
 * Получить коллекцию всех незакрытых задач на разработку, у которых в атрибуте "Планируемые версии" есть заданная.
 * @param version - версия (объект), для которой хотим получить список незакрытых задач
 * @return коллекция всех незакрытых задач на разработку, у которых в атрибуте "Планируемые версии" есть заданная
 */
def listOpenedTasksByPlannedVersion(def version)
{
	//version = utils.get('version$90310103'); //4.12.1
	def notAllowedStates = ['closed'];
	def query = api.db.query('''select t from smrmTask\$devTask t inner join t.planVersions pv where pv.id in :pv and t.state not in :states''');
	query.set('states', notAllowedStates);
	query.set('pv', [version]);
	return query.list();
}

/**
 * Отцепление релизной ветки (rc): Обрабатывает интегрированные, но возвращенные на доработку задачи с целью добавления
 * планируемой версии (develop).
 * Более формально: если задача (без разницы: разработка, АТ или дефект) уже интегрирована в rc и находится в статусе
 * отличном от “интеграции”, “ST” или “закрыта” , то предполагается, что в ней найдены проблемы и их исправления
 * требуется интегрировать как в rc, так и develop. В этом случае необходимо добавить планируемую версию develop,
 * оставив при этом rc
 * @param tasks - коллекция задач, которую требуется обработать
 * @param rcVersion - версия релиза
 * @param devVersion - версия нового develop (которую нужно добавить в планируемые). Должна быть в статусе "Отцеплена ветка"
 * @param logOnly - не производить изменений, а лишь вывести в лог список объектов, которым планируем добавить версию
 * @return список задач, попавших в данную категорию (уже интегрирована в rc и находится в статусе
 * отличном от “интеграции”, “ST” или “закрыта”)
 */
def processIntegratedButReturnedTasks(def tasks, def rcVersion, def devVersion, boolean logOnly = true)
{
	//version = utils.get('version$90310103'); //4.12.1

	//Возможно, стоит сюда добавить статусы задач АТ: ['workaround','autoTesting'] (уточнить у Саши Торбека)
	def states = ['systemTesting','closed','integration'];
	def filteredTasks = tasks?.findAll { it.fixVersions.contains(rcVersion) && !states.contains(it.state) };
	if(!logOnly)
	{
		// api.tx.call {
		filteredTasks.each {
			def versions = [];
			versions.addAll(it.planVersions);
			versions << devVersion;
			utils.edit(it, ['planVersions':versions]);
		}
		// }
	}
	logObjects(filteredTasks, "IntegratedButReturnedTasks: ");
	return filteredTasks;
}

/**
 * Отцепление релизной ветки (rc): Обрабатывает еще неинтегрированные в rc задачи на разработку:
 * если задача на разработку еще не интегрирована в rc, значит мы ее там не ждем. Для таких задач:
 *  - изменяем планируемую версию с rc на develop
 *  - при наличии созданных интеграций, меняем у них:
 *    а) целевую версию (develop вместо rc);
 *    б) сборку (в теории должна измениться сама, нужно проверить)
 * @param tasks - коллекция задач, которую требуется обработать
 * @param rcVersion - версия релиза
 * @param devVersion - версия нового develop (которую нужно добавить в планируемые). Должна быть в статусе "Отцеплена ветка"
 * @param logOnly - не производить изменений, а лишь вывести в лог список объектов, которым планируем изменить версию
 * @return список задач, попавших в данную категорию (задача на разработку еще не интегрирована в rc)
 */
def processNotIntegratedDevTasks(def tasks, def rcVersion, def devVersion, boolean logOnly = true)
{
	//коды типов задач на разработку, кроме АТ ('atdevelopment')
	def types = ['development', 'mobDevelopment', 'dapdevelopment', 'portaldevelop', 'releaseStab'];

	def filteredTasks = tasks?.findAll { !it.fixVersions.contains(rcVersion) && types.contains(it.metaClass.getCode()) };
	if(!logOnly)
	{
		//изменяем планируемую версию с rc на develop
		filteredTasks.each {
			def versions = [];
			versions.addAll(it.planVersions);
			versions.remove(rcVersion);
			versions << devVersion;
			utils.edit(it, ['planVersions':versions]);
		}
	}
	logObjects(filteredTasks, "NotIntegratedDevTasks: ");
	//при наличии созданных интеграций, меняем у них целевую версию (develop вместо rc)
	processIntegrations(filteredTasks, rcVersion, devVersion, logOnly);
	return filteredTasks;
}

/**
 * Отцепление релизной ветки (rc): Обрабатывает еще неинтегрированные в rc дефекты и задачи АТ:
 * если дефект или задача АТ еще не интегрированы в rc, то поскольку это дефект - мы должны его туда интегрировать.
 * В этом случае необходимо добавить планируемую версию develop, оставив при этом rc
 *
 * @param tasks - коллекция задач, которую требуется обработать
 * @param rcVersion - версия релиза
 * @param devVersion - версия нового develop (которую нужно добавить в планируемые). Должна быть в статусе "Отцеплена ветка"
 * @param logOnly - не производить изменений, а лишь вывести в лог список объектов, которым планируем изменить версию
 * @return список задач, попавших в данную категорию (задача на разработку еще не интегрирована в rc)
 */
def processNotIntegratedBugsAndAT(def tasks, def rcVersion, def devVersion, boolean logOnly = true)
{
	//коды типов дефектов и задач на разработку АТ
	def types = ['bug', 'dapbug', 'portalbug', 'mobilebug', 'atdevelopment'];

	def filteredTasks = tasks?.findAll { !it.fixVersions.contains(rcVersion) && types.contains(it.metaClass.getCode()) };
	if(!logOnly)
	{
		//добавляем планируемую версию develop
		filteredTasks.each {
			def versions = [];
			versions.addAll(it.planVersions);
			versions << devVersion;
			utils.edit(it, ['planVersions':versions]);
		}
	}
	logObjects(filteredTasks, "NotIntegratedBugsAndAT: ");
	return filteredTasks;
}


/**
 * Обновляем версию в связанных с задачами незакрытых интеграциях
 * @param tasks - задачи для интеграций которых необходимо изменить версию
 * @param srcVersion - исходная версия
 * @param destVersion - версия, на которую нужно обновить
 * @param logOnly - не производить изменений, а лишь вывести в лог список объектов, которым планируем изменить версию
 * @return integrations - интеграции, для которых были изменены версии
 */
def processIntegrations(def tasks, def srcVersion, def destVersion, boolean logOnly = true)
{
	def states = ['closed'];
	def integrations = utils.find('integration', ['versionLink': srcVersion, 'issue': tasks]);
	integrations = integrations?.findAll { !states.contains(it.state) };
	if (!logOnly) {
		integrations.each {
			changeIntegrationVersion(it, destVersion);
		}
	}
	logObjects(integrations, "Integrations: ");
	return integrations;
}

/**
 * Изменяет версию у интеграции, а также производит все сопутствующие действия, то есть изменяет:
 *  - версию;
 *  - целевую ветку;
 *  - название задачи интеграции (в Jenkins);
 *  - ссылку на задачу интеграции (в Jenkins)
 * @param integration - интеграция, для которой требуется изменить версию
 * @param version - целевая версия для интеграции
 * @return интеграция, для которой была изменена версия
 */
def changeIntegrationVersion(def integration, def version)
{
	def product = utils.get('product$117101');//Naumen Service Management Platform
	def developVersion = modules.integrationModule.getDevelopVersion(product);
	def targetBranch = version == developVersion ? 'develop' : version.branchLink.text;
			utils.edit(integration, ['versionLink': version,
									 'targetBranch': targetBranch,
									 'buildName': modules.integrationModule.getBuildName(targetBranch, integration.issue),
									 'buildLink': modules.integrationModule.getIntegrationJobLink(integration)]);
	return integration;
}

/**
 * Шаблон для запуска ДПС
 */
def updateVersions(def rcVersion, def devVersion, boolean logOnly = true)
{
	//def rcVersion = utils.get('version$90310103'); //4.12.1
	//def rcVersion = utils.get('version$94565304'); //4.11.5.4

	//def devVersion = utils.get('version$94565304'); //4.11.5.4
	//def devVersion = utils.get('version$90310103'); //4.12.1

	//Строка для агрегации всего произошедшего в читаемом (html) виде
	def result = '';

	//Получаем все незакрытых задачи, у которых в атрибуте "Планируемые версии" есть rc:
	def tasks = listOpenedTasksByPlannedVersion(rcVersion);
	result = result + makeRecap(tasks,'Все незакрытые задачи, у которых в атрибуте "Планируемые версии" есть rc');
	logObjects(tasks, "All tasks: ");

	// 1. Если задача (без разницы: разработка, АТ или дефект) уже интегрирована в rc и находится в статусе отличном
	// от “интеграции”, “ST” или “закрыта” , то предполагается, что в ней найдены проблемы и их исправления
	// требуется интегрировать как в rc, так и develop.
	// В этом случае необходимо добавить планируемую версию develop, оставив при этом rc
	def integratedReturnedTasks = processIntegratedButReturnedTasks(tasks, rcVersion, devVersion, logOnly);
	result = result + makeRecap(integratedReturnedTasks,'Задачи, интегрированные в rc в статусах, отличных от “интеграции”, “ST” или “закрыта”. Добавляем develop')


	// 2. Если задача (без разницы: разработка, АТ или дефект) уже интегрирована в rc и находится в статусе
	// “интеграции”, “ST” или “закрыта” , то считаем, что пока все как надо и делать ничего не нужно.
	// Однако в случае нахождения проблем в ST и последующем переводе в статус “в работе”->”MT”->”Интеграции”
	// ожидаем, что она не перейдет в SТ без наличия интеграции исправлений в develop (должна быть проверка как
	// автоматическая, так и тестировщиком)
	def states = ['systemTesting','closed','integration'];
	def integratedTasks = tasks?.findAll { it.fixVersions.contains(rcVersion) && states.contains(it.state) };
	result = result + makeRecap(integratedTasks,'Задачи, интегрированные в rc в статусах “интеграции”, “ST” или “закрыта”. Ничего не делаем')
	//Ничего с ними не делаем, просто выведем для информации в лог
	logObjects(integratedTasks, "Integrated tasks: ");


	// 3. Для задач на разработку: если задача еще не интегрирована в rc - значит мы ее там не ждем. Для таких задач:
	//   - изменяем планируемую версию с rc на develop
	//   - при наличии созданных интеграций, меняем у них:
	//     * целевую версию (develop вместо rc);
	//     * сборку и сопутствующие артефакты
	def notIntegratedDevTasks = processNotIntegratedDevTasks(tasks, rcVersion, devVersion, logOnly);
	result = result +  makeRecap(notIntegratedDevTasks,'Задачи на разработку, еще неинтегрированные в rc. Мы их не ждем. Изменяем планируемую версию rc на develop')


	// 4. Для незакрытых дефектов и задач АТ: если задача еще не интегрирована в rc, то поскольку это
	// дефект - мы должны его туда интегрировать. В этом случае необходимо добавить планируемую версию develop,
	// оставив при этом rc
	def notIntegratedBugsAndAT = processNotIntegratedBugsAndAT(tasks, rcVersion, devVersion, logOnly);
	result = result +  makeRecap(notIntegratedBugsAndAT,'Дефекты и задачи АТ, еще неинтегрированные в rc. Нужны везде. Добавляем develop, rc тоже оставляем')

	return result;

}

/**
 * Выводим список объектов в лог
 * @param objects
 * @return
 */
def logObjects(def objects, String messagePrefix, String commonPrefix = "RC: ")
{
	String prefix = messagePrefix ? commonPrefix + messagePrefix + ": " : commonPrefix;
	logger.info(prefix + " logging begin ******************************");
	logger.info(prefix + " objects count: " + objects?.size());
	objects?.each{
		logger.info("$prefix ($it.UUID) : $it.title : $it.state")
	}
	logger.info(prefix + " logging end   ******************************");
}

String makeRecap(def objects, String prefix)
{
	def result = '<br/><u>' << (prefix ? prefix : '') << ': count = ' << objects?.size() <<'</u>:<br/>';
	objects?.each{
		result << '&nbsp;&nbsp;&nbsp;' + it.metaClass.code + ' : ' + it.UUID + ' : ' + it.title.split(' ')[0] + ' : ' + it.state + ';<br/>'
	}
	return result;
}
// *************** </Отцепление релизной ветки (rc)> ***************

// *************** <Создание задач на стабилизацию и верификацию> ***************

def createVerificationTask(parent, project, step, author, responsible, deadline) {
	def params = [
			'author' : author,
			'shortTitle' : 'Верификация релизной задачи ' + parent.title,
			'responsibleEmployee' : responsible,
			'responsibleTeam' : responsible.defaultteam,
			'relReleaseTask' : parent,
			'description' : 'Необходимо убедиться, что в релизной задаче ' + parent.title + ' завершена аналитика, разработка, закоммичена контекстная справка, завершена подготовка документации, списаны все трудозатраты и закрыты все прочие задачи, после чего закрыть данную задачу верификации и перевести релизную задачу в статус "Готова"',
			'projectpp' : project,
			'stepsLink' : step,
			'product' : parent.release.parent,
			'deadline' : deadline,
			'state' : 'plan',
			'skipWorkflowOperations' : true
	]
	return api.tx.call{utils.create('smrmTask$verification', params)}
}

def createStabilizationTask(releaseTask, title, description, project, step, author, responsible, respDev, respAnalyst, deadline, product) {
	def params = [
			'author' : author,
			'shortTitle' : title,
			'responsibleEmployee' : responsible,
			'responsibleTeam' : responsible.defaultteam,
			'relReleaseTask' : releaseTask,
			'description' : description,
			'projectpp' : project,
			'stepsLink' : step,
			'passAfterT' : true,
			'passAfterR' : true,
			'product' : product,
			'deadline' : deadline,
			'priority' : utils.get('prioritySMRM$39807902'),
			'respAnalyst' : respAnalyst,
			'state' : 'plan',
			'skipWorkflowOperations' : true,
			'respDevelop' : respDev
	]
	return api.tx.call{utils.create('smrmTask$releaseStab', params)}
}

/**
 * Создать задачи на стабилизацию и верификацию
 * @param release - релиз, для которого создаем задачи. По сути subject, если делать кнопку-скрипт на карточке релиза. (Пример: utils.get('release$63532701'))
 * @param deadline - срок у создаваемых задач (utils.formatters.strToDateTime('12.08.2019 00:00'))
 * @param author - автор задачи (пример: utils.get('employee',['login':'amokhov']))
 * @param respTest - ответственный тестировщие по умолчанию (utils.get('employee$1448') // Лоскутова)
 * @param respAnalyst - ответственный аналитик по умолчанию (utils.get('employee$1734') // Котельников)
 * @param respDev - ответственный разработчик по умолчанию (utils.get('employee',['login':'amokhov']) // Мохов)
 * @return все созданные задачи
 */
def createStabilizationAndVerificationTasks(def release, def deadline, def author, def respTest,
											def respAnalyst, def respDev, def portalRespAnalyst, def portalRespDev)
{
	def project = release.project; // проект в рамках релиза
	def step = null; //utils.get('stepProject$61624301') // TODO нужно вводить этапы проекта
	def tasks = utils.find('releaseTask', ['release' : release, 'removed' : false, 'state' : op.not('registered','awaiting','analytics')])//текущие задачи
	def descriptionStab;
	def createdTasks = [];
	tasks.each{ task->
		if (task.smrmTasks?.size() > 0)
		{
			if (task.respEmployeeA && !(task.respEmployeeA.removed))
			{
				respAnalyst = task.respEmployeeA
			}
			if(task.respD_em && !(task.respD_em.removed))
			{
				respDev = task.respD_em;
			}
			createdTasks << createVerificationTask(task, project, step, author, respAnalyst, deadline);
			descriptionStab = """Необходимо протестировать весь функционал, реализованный в рамках релизной задачи ${task.title} согласно постановке.<br/>
	Убедиться, что он реализован корректно, коммит внесен в релизную ветку.<br/>
	ST всех задач завершен, автотесты реализованы.<br/>
	После чего закрыть текущую задачу."""
			createdTasks << createStabilizationTask(task, ('Стабилизация релизной задачи ' + task.title), descriptionStab,
					project, step, author, respTest, respDev, respAnalyst, deadline, task.release.parent);
		}
	}
	// Создать задачи для стабилизации портала:
	//  - "Тест совместимости с порталом SD Pro"
	//  - "Тест совместимости с порталом ППВ"
	//Связанный продукт Portal, отв. разработчик - Паша Зыков, отв. аналитик - Андрей Иванов, отв. тестировщик - Наташа Лоскутова.
	def portalProduct = utils.get('product$57347201');//Продукт "Naumen SMP Portal"
	def portalProject = utils.get('allprojects$62864823');//Проект внутренний "ВН. Релиз SMP Портал"
	def portalTasksTitles = ["Тест совместимости с порталом SD Pro", "Тест совместимости с порталом ППВ"];
	portalTasksTitles.each {
		createdTasks << createStabilizationTask(null, it, it,
				portalProject, step, author, respTest, portalRespDev, portalRespAnalyst, deadline, portalProduct);
	}

	return createdTasks;
}
// *************** </Создание задач на стабилизацию и верификацию> ***************
