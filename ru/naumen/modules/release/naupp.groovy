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
	logObjects(filteredTasks, "");
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
		filteredTasks.each {
			def versions = [];
			versions.addAll(it.planVersions);
			versions.remove(rcVersion);
			versions << devVersion;
			utils.edit(it, ['planVersions':versions]);
		}
	}
	logObjects(filteredTasks, "");
	return filteredTasks;
}

/**
 * Обновляем версию в связанных с задачами интеграциях
 * @param tasks - задачи для интеграций которых необходимо изменить версию
 * @param srcVersion - исходная версия
 * @param destVersion - версия, на которую нужно обновить
 * @param logOnly - не производить изменений, а лишь вывести в лог список объектов, которым планируем изменить версию
 * @return integrations - интеграции, для которых были изменены версии
 */
def processIntegrations(def tasks, def srcVersion, def destVersion, boolean logOnly = true)
{
	def integrations = utils.find('integration', ['versionLink': srcVersion, 'issue': tasks]);
	if (!logOnly) {
		integrations.each {
			changeIntegrationVersion(it, destVersion)
		}
	}
	logObjects(integrations, "");
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
def updateVersions()
{
	def rcVersion = utils.get('version$90310103'); //4.12.1
	//def devVersion = utils.get('version$82730402'); //4.9.0.47 (для теста)
	def devVersion = utils.get('version$78288806'); //4.8.0.59
	def tasks = listOpenedTasksByPlannedVersion(rcVersion);
	processIntegratedButReturnedTasks(tasks, rcVersion, devVersion, true);
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

// *************** </Отцепление релизной ветки (rc)> ***************