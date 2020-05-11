/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.exporter.context;

import io.zeebe.exporter.api.context.Configuration;
import io.zeebe.exporter.api.context.Context;
import io.zeebe.protocol.record.RecordType;
import io.zeebe.protocol.record.ValueType;
import io.zeebe.util.EnsureUtil;
import org.slf4j.Logger;

public class ExporterContext implements Context {

  private static final RecordFilter DEFAULT_FILTER = new AcceptAllRecordsFilter();

  private final Logger logger;
  private final Configuration configuration;

  private RecordFilter filter = DEFAULT_FILTER;

  public ExporterContext(final Logger logger, final Configuration configuration) {
    this.logger = logger;
    this.configuration = configuration;
  }

  @Override
  public Logger getLogger() {
    return logger;
  }

  @Override
  public Configuration getConfiguration() {
    return configuration;
  }

  @Override
  public void setFilter(RecordFilter filter) {
    EnsureUtil.ensureNotNull("filter", filter);
    this.filter = filter;
  }

  public RecordFilter getFilter() {
    return filter;
  }

  private static class AcceptAllRecordsFilter implements RecordFilter {

    @Override
    public boolean acceptType(RecordType recordType) {
      return true;
    }

    @Override
    public boolean acceptValue(ValueType valueType) {
      return true;
    }
  }
}