CREATE SEQUENCE parking_entrance_for_vehicles_seq
    START WITH 1
    INCREMENT BY 10
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE parking_entrance_for_vehicles (
    all_areas_wheelchair_accessible boolean,
    covered smallint CHECK ((covered BETWEEN 0 AND 4)),
    drop_off_point_close boolean,
    dropped_kerb_outside boolean,
    height numeric(38,2),
    is_entry boolean,
    is_exit boolean,
    is_external boolean,
    width numeric(38,2),
    description_lang character varying(5),
    name_lang character varying(5),
    accessibility_assessment_id bigint UNIQUE,
    changed timestamp(6) without time zone,
    created timestamp(6) without time zone,
    from_date timestamp(6) without time zone,
    id bigint NOT NULL,
    label_id bigint UNIQUE,
    multi_surface_id bigint UNIQUE,
    place_equipments_id bigint UNIQUE,
    polygon_id bigint UNIQUE,
    to_date timestamp(6) without time zone,
    version bigint NOT NULL,
    description_value character varying(4000),
    changed_by character varying(255),
    entrance_type character varying(255) CHECK ((entrance_type IN ('OPENING','OPEN_DOOR','DOOR','SWING_DOOR','REVOLVING_DOOR','AUTOMATIC_DOOR','TICKET_BARRIER','GATE','OTHER'))),
    level_ref character varying(255),
    level_ref_version character varying(255),
    name_value character varying(255),
    netex_id character varying(255),
    private_code_type character varying(255),
    private_code_value character varying(255),
    public_code character varying(255),
    site_ref character varying(255),
    site_ref_version character varying(255),
    version_comment character varying(255),
    centroid geometry,
    CONSTRAINT parking_entrance_for_vehicles_pkey PRIMARY KEY (id)
);

CREATE TABLE parking_entrance_for_vehicles_alternative_names (
    alternative_names_id bigint NOT NULL UNIQUE,
    parking_entrance_for_vehicles_id bigint NOT NULL
);

CREATE TABLE parking_entrance_for_vehicles_check_constraints (
    check_constraints_id bigint NOT NULL UNIQUE,
    parking_entrance_for_vehicles_id bigint NOT NULL
);

CREATE TABLE parking_entrance_for_vehicles_equipment_places (
    equipment_places_id bigint NOT NULL UNIQUE,
    parking_entrance_for_vehicles_id bigint NOT NULL
);

CREATE TABLE parking_entrance_for_vehicles_facilities (
    facilities_id bigint NOT NULL UNIQUE,
    parking_entrance_for_vehicles_id bigint NOT NULL,
    CONSTRAINT parking_entrance_for_vehicles_facilities_pkey PRIMARY KEY (facilities_id, parking_entrance_for_vehicles_id)
);

CREATE TABLE parking_entrance_for_vehicles_key_values (
    key_values_id bigint NOT NULL UNIQUE,
    parking_entrance_for_vehicles_id bigint NOT NULL,
    key_values_key character varying(255) NOT NULL,
    CONSTRAINT parking_entrance_for_vehicles_key_values_pkey PRIMARY KEY (parking_entrance_for_vehicles_id, key_values_key)
);

CREATE TABLE parking_entrance_for_vehicles_local_services (
    local_services_id bigint NOT NULL UNIQUE,
    parking_entrance_for_vehicles_id bigint NOT NULL
);

CREATE TABLE parking_vehicle_entrances (
    parking_id bigint NOT NULL,
    vehicle_entrances_id bigint NOT NULL UNIQUE
);

ALTER TABLE ONLY parking_entrance_for_vehicles
    ADD CONSTRAINT parking_entrance_for_vehicles_multi_surface_fk FOREIGN KEY (multi_surface_id) REFERENCES persistable_multi_polygon(id);
ALTER TABLE ONLY parking_entrance_for_vehicles
    ADD CONSTRAINT parking_entrance_for_vehicles_polygon_fk FOREIGN KEY (polygon_id) REFERENCES persistable_polygon(id);
ALTER TABLE ONLY parking_entrance_for_vehicles
    ADD CONSTRAINT parking_entrance_for_vehicles_accessibility_assessment_fk FOREIGN KEY (accessibility_assessment_id) REFERENCES accessibility_assessment(id);
ALTER TABLE ONLY parking_entrance_for_vehicles
    ADD CONSTRAINT parking_entrance_for_vehicles_place_equipments_fk FOREIGN KEY (place_equipments_id) REFERENCES installed_equipment_version_structure(id);
ALTER TABLE ONLY parking_entrance_for_vehicles
    ADD CONSTRAINT parking_entrance_for_vehicles_label_fk FOREIGN KEY (label_id) REFERENCES multilingual_string_entity(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_alternative_names
    ADD CONSTRAINT parking_entrance_for_vehicles_alternative_names_value_fk FOREIGN KEY (alternative_names_id) REFERENCES alternative_name(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_alternative_names
    ADD CONSTRAINT parking_entrance_for_vehicles_alternative_names_owner_fk FOREIGN KEY (parking_entrance_for_vehicles_id) REFERENCES parking_entrance_for_vehicles(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_check_constraints
    ADD CONSTRAINT parking_entrance_for_vehicles_check_constraints_value_fk FOREIGN KEY (check_constraints_id) REFERENCES check_constraint(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_check_constraints
    ADD CONSTRAINT parking_entrance_for_vehicles_check_constraints_owner_fk FOREIGN KEY (parking_entrance_for_vehicles_id) REFERENCES parking_entrance_for_vehicles(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_equipment_places
    ADD CONSTRAINT parking_entrance_for_vehicles_equipment_places_value_fk FOREIGN KEY (equipment_places_id) REFERENCES equipment_place(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_equipment_places
    ADD CONSTRAINT parking_entrance_for_vehicles_equipment_places_owner_fk FOREIGN KEY (parking_entrance_for_vehicles_id) REFERENCES parking_entrance_for_vehicles(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_facilities
    ADD CONSTRAINT parking_entrance_for_vehicles_facilities_value_fk FOREIGN KEY (facilities_id) REFERENCES site_facility_set(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_facilities
    ADD CONSTRAINT parking_entrance_for_vehicles_facilities_owner_fk FOREIGN KEY (parking_entrance_for_vehicles_id) REFERENCES parking_entrance_for_vehicles(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_key_values
    ADD CONSTRAINT parking_entrance_for_vehicles_key_values_value_fk FOREIGN KEY (key_values_id) REFERENCES value(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_key_values
    ADD CONSTRAINT parking_entrance_for_vehicles_key_values_owner_fk FOREIGN KEY (parking_entrance_for_vehicles_id) REFERENCES parking_entrance_for_vehicles(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_local_services
    ADD CONSTRAINT parking_entrance_for_vehicles_local_services_value_fk FOREIGN KEY (local_services_id) REFERENCES local_service(id);
ALTER TABLE ONLY parking_entrance_for_vehicles_local_services
    ADD CONSTRAINT parking_entrance_for_vehicles_local_services_owner_fk FOREIGN KEY (parking_entrance_for_vehicles_id) REFERENCES parking_entrance_for_vehicles(id);
ALTER TABLE ONLY parking_vehicle_entrances
    ADD CONSTRAINT parking_vehicle_entrances_vehicle_entrances_fk FOREIGN KEY (vehicle_entrances_id) REFERENCES parking_entrance_for_vehicles(id);
ALTER TABLE ONLY parking_vehicle_entrances
    ADD CONSTRAINT parking_vehicle_entrances_parking_fk FOREIGN KEY (parking_id) REFERENCES parking(id);
