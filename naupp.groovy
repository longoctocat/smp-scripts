/**
 * Модуль, содержащий полезные функции для работы с naupp
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
 