from openhab import Registry, logger

import java

import uuid
import re

Java_ConditionBuilder = java.type("org.openhab.core.automation.util.ConditionBuilder")
Java_TriggerBuilder = java.type("org.openhab.core.automation.util.TriggerBuilder")
Java_Configuration = java.type("org.openhab.core.config.core.Configuration")

Java_Command = java.type("org.openhab.core.types.Command")
Java_State = java.type("org.openhab.core.types.State")

def validate_uid(uid):
    if uid is None:
        uid = uuid.uuid1().hex
    else:
        uid = re.sub(r"[^A-Za-z0-9_-]", "_", uid)
        uid = "{}_{}".format(uid, uuid.uuid1().hex)
    if not re.match("^[A-Za-z0-9]", uid):# in case the first character is still invalid
        uid = "{}_{}".format("jython", uid)
    uid = re.sub(r"__+", "_", uid)
    return uid

class ItemStateChangeTrigger():
    def __init__(self, item_name, state=None, previous_state=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"itemName": item_name}
        if state is not None:
            configuration["state"] = str(state) if java.instanceof(state, Java_State) else state
        if previous_state is not None:
            configuration["previousState"] = str(previous_state) if java.instanceof(previous_state, Java_State) else previous_state
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.ItemStateChangeTrigger").withConfiguration(Java_Configuration(configuration)).build()

class ItemStateUpdateTrigger():
    def __init__(self, item_name, state=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"itemName": item_name}
        if state is not None:
            configuration["state"] = str(state) if java.instanceof(state, Java_State) else state
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.ItemStateUpdateTrigger").withConfiguration(Java_Configuration(configuration)).build()

class ItemCommandTrigger():
    def __init__(self, item_name, command=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"itemName": item_name}
        if command is not None:
            configuration["command"] = str(command) if java.instanceof(command, Java_Command) else command
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.ItemCommandTrigger").withConfiguration(Java_Configuration(configuration)).build()

class GroupStateChangeTrigger():
    def __init__(self, group_name, state=None, previous_state=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"groupName": group_name}
        if state is not None:
            configuration["state"] = str(state) if java.instanceof(state, Java_State) else state
        if previous_state is not None:
            configuration["previousState"] = previous_state
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.GroupStateChangeTrigger").withConfiguration(Java_Configuration(configuration)).build()

class GroupStateUpdateTrigger():
    def __init__(self, group_name, state=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"groupName": group_name}
        if state is not None:
            configuration["state"] = str(state) if java.instanceof(state, Java_State) else state
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.GroupStateUpdateTrigger").withConfiguration(Java_Configuration(configuration)).build()

class GroupCommandTrigger():
    def __init__(self, group_name, command=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"groupName": group_name}
        if command is not None:
            configuration["command"] = command
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.GroupCommandTrigger").withConfiguration(Java_Configuration(configuration)).build()

class ThingStatusUpdateTrigger():
    def __init__(self, thing_uid, status=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"thingUID": thing_uid}
        if status is not None:
            configuration["status"] = status
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.ThingStatusUpdateTrigger").withConfiguration(Java_Configuration(configuration)).build()

class ThingStatusChangeTrigger():
    def __init__(self, thing_uid, status=None, previous_status=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"thingUID": thing_uid}
        if status is not None:
            configuration["status"] = status
        if previous_status is not None:
            configuration["previousStatus"] = previous_status
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.ThingStatusChangeTrigger").withConfiguration(Java_Configuration(configuration)).build()

class ChannelEventTrigger():
    def __init__(self, channel_uid, event=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {"channelUID": channel_uid}
        if event is not None:
            configuration["event"] = event
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.ChannelEventTrigger").withConfiguration(Java_Configuration(configuration)).build()
        return self

class SystemStartlevelTrigger():
    def __init__(self, startlevel, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = { "startlevel": startlevel }
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.SystemStartlevelTrigger").withConfiguration(Java_Configuration(configuration)).build()

class GenericCronTrigger():
    def __init__(self, cron_expression, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {'cronExpression': cron_expression}
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("timer.GenericCronTrigger").withConfiguration(Java_Configuration(configuration)).build()

class TimeOfDayTrigger():
    def __init__(self, time, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = { 'time': time }
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("timer.TimeOfDayTrigger").withConfiguration(Java_Configuration(configuration)).build()

class DateTimeTrigger():
    def __init__(self, item_name, time_only = False, offset = 0, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = { "itemName": item_name, "timeOnly": time_only, "offset": offset }
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("timer.DateTimeTrigger").withConfiguration(Java_Configuration(configuration)).build()

class PWMTrigger():
    def __init__(self, dutycycle_item, interval, min_duty_cycle, max_duty_cycle, dead_man_switch, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        configuration = {
            "dutycycleItem": dutycycle_item,
            "interval": interval,
            "minDutycycle": min_duty_cycle,
            "maxDutycycle": max_duty_cycle,
            "deadManSwitch": dead_man_switch
        }
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("pwm.PWMTrigger").withConfiguration(Java_Configuration(configuration)).build()

class GenericEventTrigger():
    def __init__(self, event_source, event_types, event_topic="*/*", trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.GenericEventTrigger").withConfiguration(Java_Configuration({
            "eventTopic": event_topic,
            "eventSource": event_source,
            "eventTypes": event_types
        })).build()

class ItemEventTrigger():
    def __init__(self, event_types, item_name=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.GenericEventTrigger").withConfiguration(Java_Configuration({
            "eventTopic": "*/items/*",
            "eventSource": "/items/{}".format(item_name if item_name else ""),
            "eventTypes": event_types
        })).build()

class ThingEventTrigger():
    def __init__(self, event_types, thing_uid=None, trigger_name=None):
        trigger_name = validate_uid(trigger_name)
        self.raw_trigger = Java_TriggerBuilder.create().withId(trigger_name).withTypeUID("core.GenericEventTrigger").withConfiguration(Java_Configuration({
            "eventTopic": "*/things/*",
            "eventSource": "/things/{}".format(thing_uid if thing_uid else ""),
            "eventTypes": event_types
        })).build()

class when():
    triggers = []

    def __init__(self, term_as_string):
        self.term_as_string = term_as_string
        if len(when.triggers) == 0:     # only init class variables once
            when.triggers = [
                # (first_word, trigger_class, regex or parse_function, map_function)
                ("item", ItemStateUpdateTrigger, r"^Item\s+(?P<item_name>\D\w*)\s+received\s+update(?:\s+(?P<state>'[^']+'|\S+))*$", None),
                ("item", ItemStateChangeTrigger, r"^Item\s+(?P<item_name>\D\w*)\s+changed(?:\s+from\s+(?P<previous_state>'[^']+'|\S+))*(?:\s+to\s+(?P<state>'[^']+'|\S+))*$", None),
                ("item", ItemCommandTrigger, r"^Item\s+(?P<item_name>\D\w*)\s+received\s+command(?:\s+(?P<command>\w+))*$", None),
                ("member", GroupStateUpdateTrigger, r"^Member\s+of\s+(?P<group_name>\D\w*)\s+received\s+update(?:\s+(?P<state>'[^']+'|\S+))*$", None),
                ("member", GroupStateChangeTrigger, r"^Member\s+of\s+(?P<group_name>\D\w*)\s+changed(?:\s+from\s+(?P<previous_state>'[^']+'|\S+))*(?:\s+to\s+(?P<state>'[^']+'|\S+))*$", None),
                ("member", GroupCommandTrigger, r"^Member\s+of\s+(?P<group_name>\D\w*)\s+received\s+command(?:\s+(?P<command>\w+))*$", None),
                ("thing", ThingStatusUpdateTrigger, r"^Thing\s+(?P<thing_uid>\D\S*)\s+received\s+update(?:\s+(?P<status>\w+))*$", None),
                ("thing", ThingStatusChangeTrigger, r"^Thing\s+(?P<thing_uid>\D\S*)\s+changed(?:\s+from\s+(?P<previous_status>\w+))*(?:\s+to\s+(?P<status>\w+))*$", None),
                ("channel", ChannelEventTrigger, r"^Channel\s+\"*(?P<channel_uid>\D\S*)\"*\s+triggered(?:\s+(?P<event>\w+))*$", None),
                ("system", SystemStartlevelTrigger, r"^System\s+(?:started|reached\s+start\s+level\s+(?P<startlevel>\d+))$",
                    lambda d: { 'startlevel': d['startlevel'] if d['startlevel'] is not None else 40 }),
                ("time", GenericCronTrigger, when.parseCronTrigger, None),
                ("time", TimeOfDayTrigger, r"^Time\s+is\s+(?P<time>([01]\d|2[0-3]):[0-5]\d)$", None),
                ("time", DateTimeTrigger, r"^Time\s+is\s+(?P<item_name>\D\w*)(?:\s+\[(?P<time_only>timeOnly)\])*$",
                    lambda d: { 'item_name': d['item_name'], 'time_only': d['time_only'] == "timeOnly" }),
                # ("", PWMTrigger, None, None),
                # ("", GenericEventTrigger, None, None),
                ("item", ItemEventTrigger, r"^Item\s+(?P<action>added|removed|updated)$",
                    lambda d: { 'event_types': "Item" + d['action'].capitalize() + "Event" }),
                ("thing", ThingEventTrigger, r"^Thing\s+(?P<action>added|removed|updated)$",
                    lambda d: { 'event_types': "Thing" + d['action'].capitalize() + "Event" })
        ]
        r"(([01]?\d|2[0-3]):[0-5]\d)|((0?[1-9]|1[0-2]):[0-5]\d(:[0-5]\d)?\s?(AM|PM))"

    def __call__(self, function):
        triggerClass = when.parse(self.term_as_string)

        if triggerClass == None:
            raise ValueError(f"Invalid trigger: { self.term_as_string }")

        if not hasattr(function, '_when_triggers'):
            function._when_triggers = []

        function._when_triggers.append(triggerClass)

        return function

    @classmethod
    def parse(cls, term_as_string):
        _term_as_string = term_as_string.strip()

        first_word = _term_as_string.split()[0]

        for trigger in when.triggers:
            # check it trigger is related to avoid regex
            if first_word.lower() not in trigger[0]:
                continue

            if isinstance(trigger[2], str):
                trigger_class = cls.parseGenericTrigger(trigger[1], trigger[2], _term_as_string, trigger[3])
            else:
                trigger_class = trigger[2](_term_as_string)

            if trigger_class is not None:
                return trigger_class

        raise ValueError(f"Could not parse {first_word} trigger: {_term_as_string}")

    @classmethod
    def parseGenericTrigger(cls, triggerClass, regex, clause, map=None):
        match = re.match(regex, clause, re.IGNORECASE)
        if match is not None:
            params = match.groupdict() if map is None else map(match.groupdict())
            return triggerClass(**params)

    @classmethod
    def parseCronTrigger(cls, clause):
        match = re.match(r"^Time\s+(?:cron\s+(?P<cronExpression>.*)|is\s+(?P<namedInstant>midnight|noon))$", clause, re.IGNORECASE)
        if match is not None:
            if match.group('namedInstant') is None:
                cronExpression = match.group('cronExpression')
            elif match.group('namedInstant') == "midnight":
                cronExpression = "0 0 0 * * ?"
            elif match.group('namedInstant') == "noon":
                cronExpression = "0 0 12 * * ?"
                
            if cronExpression is None:
                raise ValueError("invalid cron expression")

            return GenericCronTrigger(cron_expression=cronExpression)

class ItemStateCondition():
    def __init__(self, item_name, operator, state, condition_name=None):
        condition_name = validate_uid(condition_name)
        configuration = {
            "itemName": item_name,
            "operator": operator,
            "state": state
        }
        if any(value is None for value in configuration.values()):
            raise ValueError(u"Paramater invalid in call to ItemStateConditon")

        self.raw_condition = Java_ConditionBuilder.create().withId(condition_name).withTypeUID("core.ItemStateCondition").withConfiguration(Java_Configuration(configuration)).build()

class EphemerisCondition():
    def __init__(self, dayset, offset=0, condition_name=None):
        condition_name = validate_uid(condition_name)
        configuration = {
            "offset": offset
        }
        typeuid = {
            "holiday":      "ephemeris.HolidayCondition",
            "notholiday":   "ephemeris.NotHolidayCondition",
            "weekend":      "ephemeris.WeekendCondition",
            "weekday":      "ephemeris.WeekdayCondition"
        }.get(dayset)

        if typeuid is None:
            typeuid = "ephemeris.DaysetCondition"
            configuration['dayset'] = dayset

        self.raw_condition = Java_ConditionBuilder.create().withId(condition_name).withTypeUID(typeuid).withConfiguration(Java_Configuration(configuration)).build()

class TimeOfDayCondition():
    def __init__(self, start_time, end_time, condition_name=None):
        condition_name = validate_uid(condition_name)
        configuration = {
            "startTime": start_time,
            "endTime": end_time
        }
        if any(value is None for value in configuration.values()):
            raise ValueError(u"Paramater invalid in call to TimeOfDateCondition")

        self.raw_condition = Java_ConditionBuilder.create().withId(condition_name).withTypeUID("core.TimeOfDayCondition").withConfiguration(Java_Configuration(configuration)).build()

class onlyif():
    conditions = []
    operators = [("eq", "="), ("neq", "!="), ("lt", "<"), ("lte", "<="), ("gt", ">"), ("gte", ">=")]
    timeOfDayRegEx = r"([01]\d|2[0-3]):[0-5]\d"

    def __init__(self, term_as_string):
        self.term_as_string = term_as_string

        if len(onlyif.conditions) == 0:     # only init class variables once
            onlyif.conditions = [
                #(first_word, condition_class, regex or parse_function, map_function)
                ("item", ItemStateCondition, r"^Item\s+(?P<item_name>\w+)\s+((?P<eq>=|==|eq|equals|is)|(?P<neq>!=|not\s+equals|is\s+not)|(?P<lt><|lt|is\s+less\s+than)|(?P<lte><=|lte|is\s+less\s+than\s+or\s+equal)|(?P<gt>>|gt|is\s+greater\s+than)|(?P<gte>>=|gte|is\s+greater\s+than\s+or\s+equal))\s+(?P<state>'[^']+'|\S+)*$",
                    lambda d: { 'item_name': d['item_name'], 'state': d['state'], 'operator': next((op[1] for op in onlyif.operators if d[op[0]] is not None), None) }),
                (["today", "tomorrow", "yesterday", "it's"], EphemerisCondition, onlyif.parseEphemerisCondition, None),
                ("time", TimeOfDayCondition, r"^Time\s+(?P<start_time>" + onlyif.timeOfDayRegEx + r")(?:\s*-\s*|\s+to\s+)(?P<end_time>" + onlyif.timeOfDayRegEx + r")$", None)
            ]

    def __call__(self, clazz):
        conditionClass = onlyif.parse(self.term_as_string)

        if not hasattr(clazz, '_onlyif_conditions'):
            clazz._onlyif_conditions = []
        clazz._onlyif_conditions.append(conditionClass)
        return clazz

    @classmethod
    def parse(cls, term_as_string):
        _term_as_string = term_as_string.strip()
        first_word = _term_as_string.split()[0]

        for condition in onlyif.conditions:
            # check if condition is related to avoid regex
            if first_word.lower() not in condition[0]:
                continue

            if isinstance(condition[2], str):
                condition_class = onlyif.parseGenericCondition(conditionClass=condition[1], regex=condition[2], clause=term_as_string, map=condition[3])
            else:
                condition_class = condition[2](term_as_string)

            if condition_class is not None:
                return condition_class

        raise ValueError(u"Could not parse {} condition: '{}'".format(first_word, self.target))

    @classmethod
    def parseGenericCondition(cls, conditionClass, regex, clause, map=None):
        match = re.match(regex, clause, re.IGNORECASE)
        if match is not None:
            params = match.groupdict() if map is None else map(match.groupdict())
            return conditionClass(**params)

    @classmethod
    def parseEphemerisCondition(cls, clause):
        match = re.match(r"""^((?P<today>Today\s+is|it'*s)|(?P<plus1>Tomorrow\s+is|Today\s+plus\s+1)|(?P<minus1>Yesterday\s+was|Today\s+minus\s+1)|(Today\s+(?P<plusminus>plus|minus|offset)\s+(?P<offset>-?\d+)\s+is))\s+  # what day
                         (?P<not>not\s+)?(in\s+)?(a\s+)?                        # predicate
                         (?P<dayset>holiday|weekday|weekend|\S+)$""",          # dayset
                         clause, re.IGNORECASE | re.X)
        if match is not None:
            dayset = match.group('dayset')
            if dayset is None:
                raise ValueError(u"Invalid ephemeris type: {}".format(match.group('dayset')))

            if match.group('today') is not None:
                offset = 0
            elif match.group('plus1') is not None:
                offset = 1
            elif match.group('minus1') is not None:
                offset = -1
            elif match.group('offset') is not None:
                offset = match.group('offset')
            else:
                raise ValueError(u"Offset is not specified")
            
            if match.group('not') is not None:
                if match.group('dayset') == "holiday":
                    dayset = "notholiday"
                elif match.group('dayset') == "weekday":
                    dayset = "weekend"
                elif match.group('dayset') == "weekend":
                    dayset = "weekday"
                else:
                    raise ValueError(u"Unable to negate custom dayset: {}", match.group('dayset'))
            else:
                dayset = match.group('dayset')

            return EphemerisCondition(dayset=dayset, offset=offset)