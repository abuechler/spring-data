/*
 * DISCLAIMER
 *
 * Copyright 2017 ArangoDB GmbH, Cologne, Germany
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright holder is ArangoDB GmbH, Cologne, Germany
 */

package com.arangodb.springframework.core.convert;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.core.CollectionFactory;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.data.convert.EntityInstantiator;
import org.springframework.data.convert.EntityInstantiators;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mapping.model.MappingException;
import org.springframework.data.mapping.model.ParameterValueProvider;
import org.springframework.data.util.ClassTypeInformation;
import org.springframework.data.util.TypeInformation;
import org.springframework.util.CollectionUtils;

import com.arangodb.springframework.core.convert.resolver.ReferenceResolver;
import com.arangodb.springframework.core.convert.resolver.ResolverFactory;
import com.arangodb.springframework.core.mapping.ArangoPersistentEntity;
import com.arangodb.springframework.core.mapping.ArangoPersistentProperty;
import com.arangodb.springframework.core.mapping.ConvertingPropertyAccessor;
import com.arangodb.springframework.core.mapping.PersistentPropertyAccessor;

/**
 * @author Mark Vollmary
 *
 */
public class DefaultArangoConverter implements ArangoConverter {

	private final MappingContext<? extends ArangoPersistentEntity<?>, ArangoPersistentProperty> context;
	private final CustomConversions conversions;
	private final GenericConversionService conversionService;
	private final EntityInstantiators instantiators;
	private final ResolverFactory resolverFactory;

	public DefaultArangoConverter(
		final MappingContext<? extends ArangoPersistentEntity<?>, ArangoPersistentProperty> context,
		final CustomConversions conversions, final ResolverFactory resolverFactory) {
		super();
		this.context = context;
		this.conversions = conversions;
		this.resolverFactory = resolverFactory;
		conversionService = new DefaultConversionService();
		conversions.registerConvertersIn(conversionService);
		instantiators = new EntityInstantiators();
	}

	@Override
	public MappingContext<? extends ArangoPersistentEntity<?>, ArangoPersistentProperty> getMappingContext() {
		return context;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> R read(final Class<R> type, final DBEntity source) {
		return (R) read(ClassTypeInformation.from(type), source);
	}

	private Object read(final TypeInformation<?> type, final DBEntity source) {
		if (source == null) {
			return null;
		}
		if (conversions.hasCustomReadTarget(type.getType(), type.getType())) {
			return conversionService.convert(source, type.getType());
		}
		if (type.isCollectionLike() && DBCollectionEntity.class.isAssignableFrom(source.getClass())) {
			return readCollection(type, DBCollectionEntity.class.cast(source));
		}
		final Optional<? extends ArangoPersistentEntity<?>> entity = Optional
				.ofNullable(context.getPersistentEntity(type.getType()));
		return read(type, source, entity);
	}

	private Object readCollection(final TypeInformation<?> type, final DBCollectionEntity source) {
		final Class<?> collectionType = Collection.class.isAssignableFrom(type.getType()) ? type.getType() : List.class;
		final Class<?> componentType = getComponentType(type);
		final Collection<Object> entries = type.getType().isArray() ? new ArrayList<>()
				: CollectionFactory.createCollection(collectionType, componentType, source.size());
		for (final Object entry : source) {
			if (DBEntity.class.isAssignableFrom(entries.getClass())) {
				entries.add(read(componentType, (DBEntity) entry));
			} else if (isSimpleType(componentType)) {
				final Optional<Class<?>> customWriteTarget = Optional
						.ofNullable(conversions.getCustomWriteTarget(componentType));
				final Class<?> targetType = customWriteTarget.orElseGet(() -> componentType);
				entries.add(conversionService.convert(entry, targetType));
			} else {
				throw new MappingException("this should not happen");// TODO
			}
		}
		return entries;
	}

	private Object read(
		final TypeInformation<?> type,
		final DBEntity source,
		final Optional<? extends ArangoPersistentEntity<?>> entity2) {
		final ArangoPersistentEntity<?> entity = entity2.orElseThrow(
			() -> new MappingException("No mapping metadata found for type " + type.getType().getName()));

		final EntityInstantiator instantiatorFor = instantiators.getInstantiatorFor(entity);
		final ParameterValueProvider<ArangoPersistentProperty> provider = null; // TODO
		final Object instance = instantiatorFor.createInstance(entity, provider);
		final ConvertingPropertyAccessor accessor = new ConvertingPropertyAccessor(entity.getPropertyAccessor(instance),
				conversionService);

		entity.doWithProperties((final ArangoPersistentProperty property) -> {
			readProperty(source.get("_id"), accessor, source.get(property.getFieldName()), property);
		});
		entity.doWithAssociations((final Association<ArangoPersistentProperty> association) -> {
			final ArangoPersistentProperty property = association.getInverse();
			readProperty(source.get("_id"), accessor, source.get(property.getFieldName()), property);
		});
		return instance;
	}

	private void readProperty(
		final Object parentId,
		final ConvertingPropertyAccessor accessor,
		final Object source,
		final ArangoPersistentProperty property) {
		Optional<Object> tmp = Optional.empty();
		if (source != null) {
			if (!tmp.isPresent()) {
				final Optional<Object> ref = property.getRef()
						.flatMap(annotation -> readReference(source, property, annotation));
				if (ref.isPresent()) {
					tmp = ref;
				}
			}
		}
		for (final Optional<? extends Annotation> annotation : Arrays.asList(property.getRelations(),
			property.getFrom(), property.getTo())) {
			final Optional<Object> relation = annotation.flatMap(a -> readRelation(parentId, source, property, a));
			if (relation.isPresent()) {
				tmp = relation;
				break;
			}
		}
		accessor.setProperty(property, Optional.ofNullable(read(tmp.orElse(source), property.getTypeInformation())));
	}

	@SuppressWarnings("unchecked")
	private Optional<Object> readReference(
		final Object source,
		final ArangoPersistentProperty property,
		final Annotation annotation) {
		final Optional<ReferenceResolver> resolver = resolverFactory.getReferenceResolver(annotation);
		Optional<Object> reference = Optional.empty();
		if (resolver.isPresent()) {
			if (property.isCollectionLike()) {
				// TODO check for string collection
				final Collection<String> ids = (Collection<String>) asCollection(source);
				reference = Optional
						.ofNullable(resolver.get().resolve(ids, getComponentType(property.getTypeInformation())));
			} else {
				if (!String.class.isAssignableFrom(source.getClass())) {
					throw new MappingException(
							"Type String expected for reference but found type " + source.getClass());
				}
				reference = Optional
						.ofNullable(resolver.get().resolve(source.toString(), property.getTypeInformation().getType()));
			}
		}
		return reference;
	}

	private <A extends Annotation> Optional<Object> readRelation(
		final Object parentId,
		final Object source,
		final ArangoPersistentProperty property,
		final A annotation) {
		return resolverFactory.getRelationResolver(annotation).flatMap(resolver -> {
			if (property.isCollectionLike()) {
				return Optional.of(resolver.resolveMultiple(parentId.toString(),
					getComponentType(property.getTypeInformation()), annotation));
			} else if (source != null) {
				return Optional
						.of(resolver.resolve(source.toString(), property.getTypeInformation().getType(), annotation));
			}
			return Optional.empty();
		});
	}

	@SuppressWarnings("unchecked")
	private <T> T read(final Object source, final TypeInformation<?> type) {
		if (source == null) {
			return null;
		}
		if (conversions.hasCustomReadTarget(source.getClass(), type.getType())) {
			return (T) conversionService.convert(source, type.getType());
		} else if (isMapType(source.getClass())) {
			return (T) read(type, new DBDocumentEntity((Map<? extends String, ? extends Object>) source));
		}
		return (T) source;
	}

	@Override
	public void write(final Object source, final DBEntity sink) {
		if (source == null) {
			return;
		}
		write(source, ClassTypeInformation.from(source.getClass()), sink);
	}

	@SuppressWarnings("unchecked")
	private void write(final Object source, final TypeInformation<?> type, final DBEntity sink) {
		if (isMapType(type.getType())) {
			writeMap((Map<Object, Object>) source, sink);
			return;
		}
		if (isCollectionType(type.getType())) {
			writeCollection(source, sink);
			return;
		}
		write(source, sink, Optional.ofNullable(context.getPersistentEntity(type)));
	}

	private void write(
		final Object source,
		final DBEntity sink,
		final Optional<? extends ArangoPersistentEntity<?>> entityC) {
		final ArangoPersistentEntity<?> entity = entityC.orElseThrow(
			() -> new MappingException("No mapping metadata found for type " + source.getClass().getName()));

		final PersistentPropertyAccessor accessor = entity.getPropertyAccessor(source);

		entity.doWithProperties((final ArangoPersistentProperty property) -> {
			if (!property.isWritable()) {
				return;
			}
			final Optional<Object> propertyObj = accessor.getProperty(property);
			propertyObj.ifPresent(proObj -> {
				writeProperty(proObj, sink, property);
			});
		});
		entity.doWithAssociations((final Association<ArangoPersistentProperty> association) -> {
			final ArangoPersistentProperty inverse = association.getInverse();
			final Optional<Object> property = accessor.getProperty(inverse);
			property.ifPresent(prop -> {
				writeProperty(prop, sink, inverse);
			});
		});
	}

	@SuppressWarnings("unchecked")
	private void writeProperty(final Object source, final DBEntity sink, final ArangoPersistentProperty property) {
		if (source == null) {
			return;
		}
		final String fieldName = property.getFieldName();
		final TypeInformation<?> valueType = ClassTypeInformation.from(source.getClass());
		if (property.getRef().isPresent()) {
			if (valueType.isCollectionLike()) {
				final Collection<Object> ids = new ArrayList<>();
				for (final Object ref : createCollection(asCollection(source), property)) {
					getId(ref, property).ifPresent(id -> ids.add(id));
				}
				sink.put(fieldName, ids);
			} else {
				getId(source, property).ifPresent(id -> sink.put(fieldName, id));
			}
			return;
		}
		if (property.getRelations().isPresent()) {
			return;
		}
		if (property.getFrom().isPresent() || property.getTo().isPresent()) {
			if (!valueType.isCollectionLike()) {
				getId(source, property).ifPresent(id -> sink.put(fieldName, id));
			}
			return;
		}
		if (valueType.isCollectionLike()) {
			final DBEntity collection = new DBCollectionEntity();
			writeCollection(source, collection);
			sink.put(fieldName, collection);
			return;
		}
		if (valueType.isMap()) {
			final DBEntity map = new DBDocumentEntity();
			writeMap((Map<Object, Object>) source, map);
			sink.put(fieldName, map);
			return;
		}
		final Optional<Class<?>> customWriteTarget = Optional
				.ofNullable(conversions.getCustomWriteTarget(source.getClass()));
		final Class<?> targetType = customWriteTarget.orElseGet(() -> property.getTypeInformation().getType());
		sink.put(fieldName, conversionService.convert(source, targetType));
		return;
	}

	private void writeMap(final Map<Object, Object> source, final DBEntity sink) {
		for (final Entry<Object, Object> entry : source.entrySet()) {
			final Object key = entry.getKey();
			if (!conversions.isSimpleType(key.getClass())) {
				throw new MappingException(
						"Complexe type as Map key value is not allowed! fount type " + key.getClass());
			}
			final Object value = entry.getValue();
			final Class<? extends Object> valueType = value.getClass();
			if (conversions.isSimpleType(valueType)) {
				final Optional<Class<?>> customWriteTarget = Optional
						.ofNullable(conversions.getCustomWriteTarget(valueType));
				final Class<?> targetType = customWriteTarget.orElseGet(() -> valueType);
				sink.put(key.toString(), conversionService.convert(value, targetType));
			} else {
				final DBEntity entity = createDBEntity(valueType);
				write(value, ClassTypeInformation.from(valueType), entity);
				sink.put(key.toString(), entity);
			}
		}
	}

	private void writeCollection(final Object source, final DBEntity sink) {
		for (final Object entry : asCollection(source)) {
			final Class<? extends Object> valueType = entry.getClass();
			if (conversions.isSimpleType(valueType)) {
				final Optional<Class<?>> customWriteTarget = Optional
						.ofNullable(conversions.getCustomWriteTarget(valueType));
				final Class<?> targetType = customWriteTarget.orElseGet(() -> valueType);
				sink.add(conversionService.convert(entry, targetType));
			} else {
				final DBEntity entity = createDBEntity(valueType);
				write(entry, ClassTypeInformation.from(valueType), entity);
				sink.add(entity);
			}
		}
	}

	private Optional<Object> getId(final Object source, final ArangoPersistentProperty property) {
		return Optional.ofNullable(context.getPersistentEntity(property)).flatMap(entity -> getId(source, entity));
	}

	private Optional<Object> getId(final Object source, final ArangoPersistentEntity<?> entity) {
		return Optional.ofNullable(entity.getIdProperty())
				.flatMap(p -> entity.getPropertyAccessor(source).getProperty(p));
	}

	private Collection<?> createCollection(final Collection<?> source, final ArangoPersistentProperty property) {
		return source.stream().map(s -> conversionService.convert(s, getComponentType(property.getTypeInformation())))
				.collect(Collectors.toList());
	}

	private Class<?> getComponentType(final TypeInformation<?> type) {
		return Optional.ofNullable(type.getComponentType())
				.orElseThrow(() -> new MappingException("Can not determine collection component type")).getType();
	}

	private static Collection<?> asCollection(final Object source) {
		return (Collection.class.isAssignableFrom(source.getClass())) ? Collection.class.cast(source)
				: source.getClass().isArray() ? CollectionUtils.arrayToList(source) : Collections.singleton(source);
	}

	private DBEntity createDBEntity(final Class<?> type) {
		return isCollectionType(type) ? new DBCollectionEntity() : new DBDocumentEntity();
	}

	@Override
	public boolean isSimpleType(final Class<?> type) {
		return conversions.isSimpleType(type);
	}

	@Override
	public boolean isCollectionType(final Class<?> type) {
		return type.isArray() || Iterable.class.equals(type) || Collection.class.isAssignableFrom(type);
	}

	public boolean isMapType(final Class<?> type) {
		return Map.class.isAssignableFrom(type);
	}
}
