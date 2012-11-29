package com.dianping.phoenix.console.page.deploy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;

import org.unidal.lookup.annotation.Inject;
import org.unidal.web.mvc.PageHandler;
import org.unidal.web.mvc.annotation.InboundActionMeta;
import org.unidal.web.mvc.annotation.OutboundActionMeta;
import org.unidal.web.mvc.annotation.PayloadMeta;

import com.dianping.phoenix.console.ConsolePage;
import com.dianping.phoenix.console.dal.deploy.Deployment;
import com.dianping.phoenix.console.dal.deploy.DeploymentDetails;
import com.dianping.phoenix.deploy.DeployExecutor;
import com.dianping.phoenix.deploy.DeployManager;
import com.dianping.phoenix.deploy.DeployPlan;
import com.dianping.phoenix.deploy.model.entity.DeployModel;

public class Handler implements PageHandler<Context> {
	@Inject
	private DeployManager m_deployManager;

	@Inject
	private DeployExecutor m_deployExecutor;
	
	@Inject
	private JspViewer m_jspViewer;

	@Inject
	private KeepAliveViewer m_statusViewer;

    private int count;  //TODO remove me!

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "deploy")
	public void handleInbound(Context ctx) throws ServletException, IOException {
		// display only, no action here
	}

	@Override
	@OutboundActionMeta(name = "deploy")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();
		Action action = payload.getAction();

		model.setAction(action);
		model.setPage(ConsolePage.DEPLOY);

		switch (action) {
		case VIEW:
            count = 0;
			try {
				showView(model, payload.getId());
			} catch (Exception e) {
				ctx.addError("deploy.query", e);
			}

			m_jspViewer.view(ctx, model);
			break;
		case STATUS:
            count++;
            payload.setCount(count);
			try {
				showStatus(model, payload.getId());
			} catch (Exception e) {
				ctx.addError("deploy.status", e);
			}

            m_jspViewer.view(ctx, model);
			break;
		}
	}

	private void showStatus(Model model, int id) {

	}

	private void showView(Model model, int id) throws Exception {
		DeployModel deployModel = m_deployExecutor.getModel(id);
		Deployment deployment = m_deployManager.query(id);
		DeployPlan plan = new DeployPlan();

		plan.setVersion(deployment.getWarVersion());
		plan.setPolicy(deployment.getStrategy());
		plan.setAbortOnError("abortOnError".equals(deployment.getErrorPolicy()));
		
		model.setName(deployment.getDomain());
		model.setPlan(plan);
		model.setDeploy(deployModel);

		List<String> hosts = new ArrayList<String>();

		for (DeploymentDetails details : deployment.getDetailsList()) {
			hosts.add(details.getIpAddress());
		}

		model.setStatus("doing");
	}
}
