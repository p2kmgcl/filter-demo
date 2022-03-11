/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 * <p>
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 * <p>
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.date.filter;

import com.liferay.asset.kernel.service.AssetTagLocalService;
import com.liferay.document.library.kernel.model.DLFileEntryConstants;
import com.liferay.document.library.kernel.service.DLAppLocalService;
import com.liferay.info.collection.provider.CollectionQuery;
import com.liferay.info.collection.provider.FilteredInfoCollectionProvider;
import com.liferay.info.collection.provider.InfoCollectionProvider;
import com.liferay.info.collection.provider.SingleFormVariationInfoCollectionProvider;
import com.liferay.info.filter.CategoriesInfoFilter;
import com.liferay.info.filter.InfoFilter;
import com.liferay.info.pagination.InfoPage;
import com.liferay.info.pagination.Pagination;
import com.liferay.info.sort.Sort;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.language.LanguageUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.repository.model.FileEntry;
import com.liferay.portal.kernel.search.BooleanClause;
import com.liferay.portal.kernel.search.BooleanClauseFactoryUtil;
import com.liferay.portal.kernel.search.BooleanClauseOccur;
import com.liferay.portal.kernel.search.Document;
import com.liferay.portal.kernel.search.Field;
import com.liferay.portal.kernel.search.Hits;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.search.QueryConfig;
import com.liferay.portal.kernel.search.SearchContext;
import com.liferay.portal.kernel.search.filter.BooleanFilter;
import com.liferay.portal.kernel.search.filter.RangeTermFilter;
import com.liferay.portal.kernel.search.generic.BooleanQueryImpl;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.ServiceContextThreadLocal;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.FastDateFormatFactoryUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.HashMapBuilder;
import com.liferay.portal.kernel.util.PropsKeys;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import java.io.Serializable;

import java.text.Format;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Víctor Galán
 */
@Component(immediate = true, service = InfoCollectionProvider.class)
public class BasicDocumentSingleFormVariationInfoCollectionProvider
	implements FilteredInfoCollectionProvider<FileEntry>,
			   SingleFormVariationInfoCollectionProvider<FileEntry> {

	@Override
	public InfoPage<FileEntry> getCollectionInfoPage(
		CollectionQuery collectionQuery) {

		return _getFileEntryInfoPage(collectionQuery);
	}

	@Override
	public String getFormVariationKey() {
		return String.valueOf(0);
	}

	@Override
	public String getLabel(Locale locale) {
		return LanguageUtil.get(locale, "basic-document");
	}

	@Override
	public List<InfoFilter> getSupportedInfoFilters() {
		return Arrays.asList(new CategoriesInfoFilter(), new DateInfoFilter());
	}

	private SearchContext _buildSearchContext(CollectionQuery collectionQuery) {
		SearchContext searchContext = new SearchContext();

		searchContext.setAndSearch(true);
		searchContext.setAttributes(
			HashMapBuilder.<String, Serializable>put(
				Field.STATUS, WorkflowConstants.STATUS_APPROVED
			).put(
				"fileEntryTypeId", getFormVariationKey()
			).put(
				"head", true
			).put(
				"latest", true
			).build());

		Optional<CategoriesInfoFilter> categoriesInfoFilterOptional =
			collectionQuery.getInfoFilterOptional(CategoriesInfoFilter.class);

		if (categoriesInfoFilterOptional.isPresent()) {
			CategoriesInfoFilter categoriesInfoFilter =
				categoriesInfoFilterOptional.get();

			long[] categoryIds = ArrayUtil.append(
				categoriesInfoFilter.getCategoryIds());

			categoryIds = ArrayUtil.unique(categoryIds);

			searchContext.setAssetCategoryIds(categoryIds);
		}

		Optional<DateInfoFilter> dateInfoFilterOptional =
			collectionQuery.getInfoFilterOptional(DateInfoFilter.class);

		if (dateInfoFilterOptional.isPresent()) {
			searchContext.setBooleanClauses(
				_getDateBooleanClause(dateInfoFilterOptional.get()));
		}

		searchContext.setClassTypeIds(
			new long[] {GetterUtil.getLong(getFormVariationKey())});

		ServiceContext serviceContext =
			ServiceContextThreadLocal.getServiceContext();

		searchContext.setCompanyId(serviceContext.getCompanyId());

		Pagination pagination = collectionQuery.getPagination();

		searchContext.setEnd(pagination.getEnd());

		searchContext.setEntryClassNames(
			new String[] {DLFileEntryConstants.getClassName()});
		searchContext.setGroupIds(
			new long[] {serviceContext.getScopeGroupId()});

		Optional<Sort> sortOptional = collectionQuery.getSortOptional();

		if (sortOptional.isPresent()) {
			Sort sort = sortOptional.get();

			searchContext.setSorts(
				new com.liferay.portal.kernel.search.Sort(
					sort.getFieldName(),
					com.liferay.portal.kernel.search.Sort.LONG_TYPE,
					sort.isReverse()));
		}
		else {
			searchContext.setSorts(
				new com.liferay.portal.kernel.search.Sort(
					Field.MODIFIED_DATE,
					com.liferay.portal.kernel.search.Sort.LONG_TYPE, true));
		}

		searchContext.setStart(pagination.getStart());

		QueryConfig queryConfig = searchContext.getQueryConfig();

		queryConfig.setHighlightEnabled(false);
		queryConfig.setScoreEnabled(false);

		return searchContext;
	}

	private BooleanClause[] _getDateBooleanClause(
		DateInfoFilter infoFilterDate) {

		BooleanQueryImpl booleanQueryImpl = new BooleanQueryImpl();

		BooleanFilter booleanFilter = new BooleanFilter();

		Format format = FastDateFormatFactoryUtil.getSimpleDateFormat(
			PropsUtil.get(PropsKeys.INDEX_DATE_FORMAT_PATTERN));

		Date date = infoFilterDate.getDate();

		Calendar calendar = Calendar.getInstance();

		calendar.setTime(date);
		calendar.add(Calendar.DATE, 1);

		Date nextDate = calendar.getTime();

		RangeTermFilter rangeTermFilter = new RangeTermFilter(
			Field.CREATE_DATE, true, false, format.format(date),
			format.format(nextDate));

		booleanFilter.add(rangeTermFilter, BooleanClauseOccur.SHOULD);

		booleanQueryImpl.setPreBooleanFilter(booleanFilter);

		return new BooleanClause[] {
			BooleanClauseFactoryUtil.create(
				booleanQueryImpl, BooleanClauseOccur.MUST.getName())
		};
	}

	private InfoPage<FileEntry> _getFileEntryInfoPage(
		CollectionQuery collectionQuery) {

		try {
			Indexer<?> indexer = IndexerRegistryUtil.getIndexer(
				DLFileEntryConstants.getClassName());

			SearchContext searchContext = _buildSearchContext(collectionQuery);

			Hits hits = indexer.search(searchContext);

			List<FileEntry> fileEntries = new ArrayList<>();

			for (Document document : hits.getDocs()) {
				long classPK = GetterUtil.getLong(
					document.get(Field.ENTRY_CLASS_PK));

				fileEntries.add(_dlAppLocalService.getFileEntry(classPK));
			}

			return InfoPage.of(
				fileEntries, collectionQuery.getPagination(), hits.getLength());
		}
		catch (PortalException portalException) {
			if (_log.isWarnEnabled()) {
				_log.warn(portalException);
			}

			return null;
		}
	}

	private static final Log _log = LogFactoryUtil.getLog(
		BasicDocumentSingleFormVariationInfoCollectionProvider.class);

	@Reference
	private AssetTagLocalService _assetTagLocalService;

	@Reference
	private DLAppLocalService _dlAppLocalService;

}