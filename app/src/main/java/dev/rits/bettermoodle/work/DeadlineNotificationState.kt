package dev.rits.bettermoodle.work

fun retainedNotifiedEventIds(
    existingNotifiedIds: Set<String>,
    currentDeadlineIds: Set<String>,
    newlyNotifiedIds: Set<String>,
): Set<String> = (existingNotifiedIds intersect currentDeadlineIds) + newlyNotifiedIds
