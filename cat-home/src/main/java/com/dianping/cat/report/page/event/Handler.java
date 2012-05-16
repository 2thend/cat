package com.dianping.cat.report.page.event;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;

import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;

import com.dianping.cat.Cat;
import com.dianping.cat.configuration.ServerConfigManager;
import com.dianping.cat.consumer.event.StatisticsComputer;
import com.dianping.cat.consumer.event.model.entity.EventName;
import com.dianping.cat.consumer.event.model.entity.EventReport;
import com.dianping.cat.consumer.event.model.entity.EventType;
import com.dianping.cat.consumer.event.model.entity.Range;
import com.dianping.cat.helper.CatString;
import com.dianping.cat.report.ReportPage;
import com.dianping.cat.report.graph.AbstractGraphPayload;
import com.dianping.cat.report.graph.GraphBuilder;
import com.dianping.cat.report.page.model.spi.ModelRequest;
import com.dianping.cat.report.page.model.spi.ModelResponse;
import com.dianping.cat.report.page.model.spi.ModelService;
import com.google.gson.Gson;
import com.site.lookup.annotation.Inject;
import com.site.lookup.util.StringUtils;
import com.site.web.mvc.PageHandler;
import com.site.web.mvc.annotation.InboundActionMeta;
import com.site.web.mvc.annotation.OutboundActionMeta;
import com.site.web.mvc.annotation.PayloadMeta;

/**
 * @author sean.wang
 * @since Feb 6, 2012
 */
public class Handler implements PageHandler<Context>, Initializable {
	@Inject
	private JspViewer m_jspViewer;

	@Inject(type = ModelService.class, value = "event")
	private ModelService<EventReport> m_service;

	@Inject
	private GraphBuilder m_builder;

	@Inject
	private ServerConfigManager m_manager;

	private Map<Integer, Integer> m_map = new HashMap<Integer, Integer>();

	private StatisticsComputer m_computer = new StatisticsComputer();

	private EventName getAggregatedEventName(Payload payload) {
		String domain = payload.getDomain();
		String type = payload.getType();
		String ipAddress = payload.getIpAddress();
		String date = String.valueOf(payload.getDate());
		ModelRequest request = new ModelRequest(domain, payload.getPeriod()) //
		      .setProperty("date", date) //
		      .setProperty("type", type) //
		      .setProperty("name", "*") //
		      .setProperty("all", "true")//
		      .setProperty("ip", ipAddress);
		ModelResponse<EventReport> response = m_service.invoke(request);
		String ip = payload.getIpAddress();
		EventReport report = response.getModel();
		EventType t = report == null ? null : report.getMachines().get(ip).findType(type);

		if (t != null) {
			EventName all = t.findName("ALL");

			return all;
		} else {
			return null;
		}
	}

	private EventName getEventName(Payload payload) {
		String domain = payload.getDomain();
		String type = payload.getType();
		String name = payload.getName();
		String ipAddress = payload.getIpAddress();
		String date = String.valueOf(payload.getDate());
		String ip = payload.getIpAddress();
		ModelRequest request = new ModelRequest(domain, payload.getPeriod()) //
		      .setProperty("date", date) //
		      .setProperty("type", payload.getType())//
		      .setProperty("name", payload.getName())//
		      .setProperty("ip", ipAddress);
		;
		ModelResponse<EventReport> response = m_service.invoke(request);
		EventReport report = response.getModel();
		EventType t = report.getMachines().get(ip).findType(type);

		if (t != null) {
			EventName n = t.findName(name);

			if (n != null) {
				n.accept(m_computer);
			}

			return n;
		}

		return null;
	}

	private EventReport getReport(Payload payload) {
		String domain = payload.getDomain();
		String ipAddress = payload.getIpAddress();
		String date = String.valueOf(payload.getDate());
		ModelRequest request = new ModelRequest(domain, payload.getPeriod()) //
		      .setProperty("date", date) //
		      .setProperty("type", payload.getType())//
		      .setProperty("ip", ipAddress);

		if (m_service.isEligable(request)) {
			ModelResponse<EventReport> response = m_service.invoke(request);
			EventReport report = response.getModel();

			return report;
		} else {
			throw new RuntimeException("Internal error: no eligable event service registered for " + request + "!");
		}
	}

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "e")
	public void handleInbound(Context ctx) throws ServletException, IOException {
		// display only, no action here
	}

	@Override
	@OutboundActionMeta(name = "e")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();

		if (StringUtils.isEmpty(payload.getDomain())) {
			payload.setDomain(m_manager.getConsoleDefaultDomain());
		}

		String ip = payload.getIpAddress();
		if (StringUtils.isEmpty(ip)) {
			payload.setIpAddress(CatString.ALL_IP);
		}
		model.setIpAddress(payload.getIpAddress());
		model.setAction(payload.getAction());
		model.setPage(ReportPage.EVENT);
		model.setDisplayDomain(payload.getDomain());

		switch (payload.getAction()) {
		case VIEW:
			showReport(model, payload);
			break;
		case GRAPHS:
			showGraphs(model, payload);
			break;
		case MOBILE:
			showReport(model, payload);
			if (!StringUtils.isEmpty(payload.getType())) {
				DisplayEventNameReport report = model.getDisplayNameReport();
				Gson gson = new Gson();
				String json = gson.toJson(report);
				model.setMobileResponse(json);
			} else {
				DisplayEventTypeReport report = model.getDisplayTypeReport();
				Gson gson = new Gson();
				String json = gson.toJson(report);
				model.setMobileResponse(json);
			}
			break;
		case MOBILE_GRAPHS:
			MobileEventGraphs graphs = showMobileGraphs(model, payload);
			if (graphs != null) {
				Gson gson = new Gson();
				model.setMobileResponse(gson.toJson(graphs));
			}
			break;
		}
		if (payload.getPeriod().isCurrent()) {
			model.setCreatTime(new Date());
		} else {
			model.setCreatTime(new Date(payload.getDate() + 60 * 60 * 1000 - 1000));
		}
		m_jspViewer.view(ctx, model);
	}

	private MobileEventGraphs showMobileGraphs(Model model, Payload payload) {
		EventName name;

		if (payload.getName() == null || payload.getName().length() == 0) {
			name = getAggregatedEventName(payload);
		} else {
			name = getEventName(payload);
		}

		if (name == null) {
			return null;
		}
		MobileEventGraphs graphs = new MobileEventGraphs().display(name);
		return graphs;
	}

	@Override
	public void initialize() throws InitializationException {
		int k = 1;

		m_map.put(0, 0);

		for (int i = 0; i < 17; i++) {
			m_map.put(k, i);
			k <<= 1;
		}
	}

	private void showGraphs(Model model, Payload payload) {
		EventName name;

		if (payload.getName() == null || payload.getName().length() == 0) {
			name = getAggregatedEventName(payload);
		} else {
			name = getEventName(payload);
		}

		if (name == null) {
			return;
		}

		String graph1 = m_builder.build(new HitPayload("Hits Over Time", "Time (min)", "Count", name));
		String graph2 = m_builder.build(new FailurePayload("Failures Over Time", "Time (min)", "Count", name));

		model.setGraph1(graph1);
		model.setGraph2(graph2);
	}

	private void showReport(Model model, Payload payload) {
		try {
			EventReport report = getReport(payload);

			if (payload.getPeriod().isFuture()) {
				model.setLongDate(payload.getCurrentDate());
			} else {
				model.setLongDate(payload.getDate());
			}

			if (report != null) {
				report.accept(m_computer);
				model.setReport(report);
			}

			String type = payload.getType();
			String sorted = payload.getSortBy();
			String ip = payload.getIpAddress();

			if (!StringUtils.isEmpty(type)) {
				model.setDisplayNameReport(new DisplayEventNameReport().display(sorted, type, ip, report));
			} else {
				model.setDisplayTypeReport(new DisplayEventTypeReport().display(sorted, ip, report));
			}
		} catch (Throwable e) {
			Cat.getProducer().logError(e);
			model.setException(e);
		}
	}

	abstract class AbstractPayload extends AbstractGraphPayload {
		private final EventName m_name;

		public AbstractPayload(String title, String axisXLabel, String axisYLabel, EventName name) {
			super(title, axisXLabel, axisYLabel);

			m_name = name;
		}

		@Override
		public String getAxisXLabel(int index) {
			return String.valueOf(index * 5);
		}

		@Override
		public int getDisplayHeight() {
			return (int) (super.getDisplayHeight() * 0.7);
		}

		@Override
		public int getDisplayWidth() {
			return (int) (super.getDisplayWidth() * 0.7);
		}

		@Override
		public String getIdPrefix() {
			return m_name.getId() + "_" + super.getIdPrefix();
		}

		protected EventName getEventName() {
			return m_name;
		}

		@Override
		public int getWidth() {
			return super.getWidth() + 120;
		}

		@Override
		public boolean isStandalone() {
			return false;
		}
	}

	final class FailurePayload extends AbstractPayload {
		public FailurePayload(String title, String axisXLabel, String axisYLabel, EventName name) {
			super(title, axisXLabel, axisYLabel, name);
		}

		@Override
		public int getOffsetX() {
			return getDisplayWidth();
		}

		@Override
		protected double[] loadValues() {
			double[] values = new double[12];

			for (Range range : getEventName().getRanges()) {
				int value = range.getValue();
				int k = value / 5;

				values[k] += range.getFails();
			}

			return values;
		}
	}

	final class HitPayload extends AbstractPayload {
		public HitPayload(String title, String axisXLabel, String axisYLabel, EventName name) {
			super(title, axisXLabel, axisYLabel, name);
		}

		@Override
		protected double[] loadValues() {
			double[] values = new double[12];

			for (Range range : getEventName().getRanges()) {
				int value = range.getValue();
				int k = value / 5;

				values[k] += range.getCount();
			}

			return values;
		}
	}
}
