package ru.yamshchik.yamshchik.adapter.out.events;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;
import ru.yamshchik.yamshchik.domain.DeliveryEvent;


@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
interface DeliveryEventMessageMapper {

    @Mapping(target = "eventId", source = "id")
    @Mapping(target = "emailId", source = "emailId.value")
    DeliveryEventMessage toMessage(DeliveryEvent event);
}
