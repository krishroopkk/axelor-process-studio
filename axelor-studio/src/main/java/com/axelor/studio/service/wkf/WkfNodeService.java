/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2016 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.studio.service.wkf;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.auth.db.Permission;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.repo.PermissionRepository;
import com.axelor.meta.db.MetaField;
import com.axelor.meta.db.MetaMenu;
import com.axelor.meta.db.MetaModel;
import com.axelor.meta.db.MetaModule;
import com.axelor.meta.db.MetaSelect;
import com.axelor.meta.db.MetaSelectItem;
import com.axelor.meta.db.repo.MetaMenuRepository;
import com.axelor.meta.db.repo.MetaModelRepository;
import com.axelor.meta.db.repo.MetaSelectRepository;
import com.axelor.meta.schema.actions.ActionGroup;
import com.axelor.studio.db.ActionBuilder;
import com.axelor.studio.db.ActionSelector;
import com.axelor.studio.db.MenuBuilder;
import com.axelor.studio.db.ViewBuilder;
import com.axelor.studio.db.WkfNode;
import com.axelor.studio.db.repo.ActionBuilderRepository;
import com.axelor.studio.db.repo.MenuBuilderRepository;
import com.axelor.studio.service.ViewLoaderService;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

/**
 * Service handle processing of WkfNode. From wkfNode it generates status field
 * for related model. It will generate field's selection according to nodes and
 * add field in related ViewBuilder. Creates permissions for status related with
 * node and assign to roles selected in node. Add status menus(MenuBuilder)
 * according to menu details in WkfNode.
 * 
 * @author axelor
 *
 */
class WkfNodeService {

	private WkfService wkfService;

	private final Logger log = LoggerFactory.getLogger(getClass());

	private List<String> nodeActions;

//	@Inject
//	private MetaSelectItemRepository metaSelectItemRepo;

	@Inject
	private MetaModelRepository metaModelRepo;

	@Inject
	private PermissionRepository permissionRepo;

	@Inject
	private MenuBuilderRepository menuBuilderRepo;

	@Inject
	private MetaMenuRepository metaMenuRepo;

	@Inject
	private MetaSelectRepository metaSelectRepo;
	
	@Inject
	private ActionBuilderRepository actionBuilderRepo;
	
	@Inject
	private ViewLoaderService viewLoaderService;

	@Inject
	protected WkfNodeService(WkfService wkfService) {
		this.wkfService = wkfService;
	}

	/**
	 * Root method to access the service. It start processing of WkfNode and
	 * call different methods for that.
	 */
	protected ActionGroup process() {

		MetaModel metaModel = wkfService.workflow.getMetaModel();
		MetaField statusField = wkfService.workflow.getWkfField();
		MetaSelect metaSelect = addMetaSelect(statusField);

		nodeActions = new ArrayList<String>();
		String defaultValue = processNodes(metaSelect, statusField);
		statusField.setDefaultString(defaultValue);

		metaModel = saveModel(metaModel);

		if (!nodeActions.isEmpty()) {
			String actionName = "action-group-" + wkfService.dasherizeModel
					+ "-wkf";
			String statusCondition = "__this__?." + statusField.getName() + " != __self__?." + statusField.getName();
			return this.wkfService.createActionGroup(actionName, nodeActions,
					statusCondition);
		}

		return null;

	}

	/**
	 * Add MetaSelect in statusField, if MetaSelect of field is null.
	 * 
	 * @param statusField
	 *            MetaField to update with MetaSelect.
	 * @return MetaSelect of statusField.
	 */
	private MetaSelect addMetaSelect(MetaField statusField) {

		MetaSelect metaSelect = statusField.getMetaSelect();

		if (metaSelect == null) {
			String selectName = wkfService.getSelectName();
//			try {
//				Mapper mapper = Mapper.of(Class.forName(statusField.getMetaModel().getFullName()));
//				Property p = mapper.getProperty(statusField.getName());
//				selectName = p.getSelection();
//			} catch (ClassNotFoundException e) {
//			}
			
			metaSelect = metaSelectRepo.findByName(selectName);
			if (metaSelect == null) {
				metaSelect = new MetaSelect(selectName);
				MetaModule metaModule = wkfService.workflow.getMetaModule();
				metaSelect.setModule(metaModule.getName());
			}
			
			statusField.setMetaSelect(metaSelect);
		}
		if (metaSelect.getItems() == null) {
			metaSelect.setItems(new ArrayList<MetaSelectItem>());
		}
		
		metaSelect.clearItems();

		return metaSelect;
	}

	/**
	 * Method remove old options from metaSelect options if not found in
	 * nodeList.
	 * 
	 * @param metaSelect
	 *            MetaSelect to process.
	 * @param nodeList
	 *            WkfNode list to compare.
	 * @return Updated MetaSelect.
	 */
	private MetaSelect removeOldOptions(MetaSelect metaSelect,
			List<WkfNode> nodeList) {

		log.debug("Cleaning meta select: {}", metaSelect.getName());

		List<MetaSelectItem> itemsToRemove = new ArrayList<MetaSelectItem>();

		Iterator<MetaSelectItem> itemIter = metaSelect.getItems().iterator();

		while (itemIter.hasNext()) {
			MetaSelectItem item = itemIter.next();
			boolean found = false;
			for (WkfNode node : nodeList) {
				if (item.getValue().equals(node.getSequence().toString())) {
					found = true;
					break;
				}
			}

			if (!found) {
				itemsToRemove.add(item);
				removeOldMenus(item.getValue());
				itemIter.remove();
			}

		}

//		log.debug("Select Items to remove : {}", itemsToRemove);

//		if (!itemsToRemove.isEmpty()) {
//			removeMetaSelectItems(itemsToRemove);
//		}

		return metaSelect;
	}

//	/**
//	 * Method delete MetaSelect items according to list past. List is old items
//	 * of MetaSelect that is no longer valid.
//	 * 
//	 * @param items
//	 *            List of MetaSelectItem.
//	 */
//	@Transactional
//	public void removeMetaSelectItems(List<MetaSelectItem> items) {
//
//		for (MetaSelectItem item : items) {
//			metaSelectItemRepo.remove(item);
//		}
//	}

	@Transactional
	public void removeOldMenus(String node) {

		String nodeMenu = getName(node, "menu");
		String myMenu = "my-" + nodeMenu;

		MenuBuilder menuBuilder = menuBuilderRepo.findByName(nodeMenu);
		if (menuBuilder != null) {
			menuBuilder.setDeleteMenu(true);
			menuBuilder.setEdited(true);
			menuBuilderRepo.save(menuBuilder);
		}

		menuBuilder = menuBuilderRepo.findByName(myMenu);
		if (menuBuilder != null) {
			menuBuilder.setDeleteMenu(true);
			menuBuilder.setEdited(true);
			menuBuilderRepo.save(menuBuilder);
		}
	}

	/**
	 * Add or update items in MetaSelect according to WkfNodes.
	 * 
	 * @param metaSelect
	 *            MetaSelect to update.
	 * @return Return first item as default value for wkfStatus field.
	 */
	private String processNodes(MetaSelect metaSelect, MetaField statusField) {

		List<WkfNode> nodeList = wkfService.workflow.getNodes();

		String defaultValue = null;
		removeOldOptions(metaSelect, nodeList);
		
		for (WkfNode node : nodeList) {
			
			String option = node.getSequence().toString();
			MetaSelectItem metaSelectItem = getMetaSelectItem(metaSelect,
					option);
			if (metaSelectItem == null) {
				metaSelectItem = new MetaSelectItem();
				metaSelectItem.setValue(option);
				metaSelect.addItem(metaSelectItem);
			}

			metaSelectItem.setTitle(node.getTitle());
			metaSelectItem.setOrder(node.getSequence());

			if (defaultValue == null) {
				defaultValue = metaSelectItem.getValue();
				log.debug("Default value set: {}", defaultValue);
			}

			if (node.getParentMenu() != null
					|| node.getParentMenuBuilder() != null) {
				addNodeMenu(node, statusField);
			}

			// String name = "custom.permission." + dasherizeModel.replace("-",
			// ".") + option;
			// clearOldPermissions(name);
			// addNodePermissions(name,node);

			List<String> actions = new ArrayList<String>();
			for (ActionSelector actionSelector : node.getActionSelectorList()) {
				if (actionSelector.getMetaAction() != null) {
					actions.add(actionSelector.getMetaAction().getName());
				}
				else if (actionSelector.getActionBuilder() != null) {
					actions.add(actionSelector.getActionBuilder().getName());
				}
			}

			if (!actions.isEmpty()) {
				String name = getName(node.getName(), "action-group");
				nodeActions.add(name);
				String value = node.getSequence().toString();
				if (statusField.getTypeName().equals("String")) {
					value = "'" + value + "'";
				}
				String condition = statusField.getName() + " == " + value;
				this.wkfService.createActionGroup(name, actions, condition);
			}

		}

		return defaultValue;

	}

	/**
	 * Process WkfNode to create MenuBuilders from it if 'statusMenuEntry' or
	 * 'myMenuEntry' boolean is true.
	 * 
	 * @param node
	 *            WkfNode to process.
	 */
	private void addNodeMenu(WkfNode node, MetaField statusField) {

		String nodeName = node.getName();
		String menuName = getName(nodeName, "menu");
		String domain = getDomain(node.getSequence(), statusField);
		MetaMenu parentMenu = node.getParentMenu();
		MenuBuilder parentMenuBuilder = node.getParentMenuBuilder();
		Integer order = 1;
		if (parentMenu != null) {
			MetaMenu childMenu = metaMenuRepo
					.all()
					.filter("self.parent.name = ?1 and self.action is null",
							parentMenu.getName()).order("-order").fetchOne();
			if (childMenu != null) {
				order = childMenu.getOrder() + 1;
			}
		} else {
			MenuBuilder menuBuilder = menuBuilderRepo
					.all()
					.filter("self.menuBuilder = ?1 and self.actionBuilder is not null",
							parentMenuBuilder).order("-order").fetchOne();
			log.debug("Last menu builder found: {}", menuBuilder);

			if (menuBuilder != null) {
				order = menuBuilder.getOrder() + 1;
			}
		}

		if (node.getStatusMenuEntry()) {
			MenuBuilder menuBuilder = addMenuBuilder(node.getStatusMenuLabel(),
					menuName, domain, parentMenu, order, parentMenuBuilder, node.getWkf().getMetaModule());
			node.setStatusMenu(menuBuilder);
			order += 1;
		}

		if (node.getMyMenuEntry()) {
			domain += " AND self." + node.getMetaField().getName()
					+ " = :__user__";
			MenuBuilder menuBuilder = addMenuBuilder(node.getMyMenuLabel(),
					"my-" + menuName, domain, parentMenu, order, parentMenuBuilder, node.getWkf().getMetaModule());
			node.setMyStatusMenu(menuBuilder);
		}

	}

	private String getDomain(Integer nodeSeq, MetaField statusField) {
		
		String typeName = statusField.getTypeName();
		
		if (typeName.equals("Integer")) {
			return "self." + statusField.getName() + " = " + nodeSeq;
		}
		
		return "self." + statusField.getName() + " = '" + nodeSeq + "'";
	}

	private String getName(String node, String prefix) {

		String menuName = wkfService.inflector.simplify(node);
		menuName = menuName.toLowerCase().replace(" ", "-");
		menuName = prefix + "-" + wkfService.dasherizeModel + "-" + menuName;

		return menuName;
	}

	/**
	 * Method to save MetaModel
	 * 
	 * @param metaModel
	 *            Model to save.
	 * @return Saved MetaModel
	 */
	@Transactional
	public MetaModel saveModel(MetaModel metaModel) {
		return metaModelRepo.save(metaModel);
	}

	/**
	 * Fetch MetaSelectItem from MetaSelect according to option.
	 * 
	 * @param metaSelect
	 *            MetaSelect to search for item.
	 * @param option
	 *            Option to search.
	 * @return MetaSelctItem found or null.
	 */
	private MetaSelectItem getMetaSelectItem(MetaSelect metaSelect,
			String option) {

		for (MetaSelectItem selectItem : metaSelect.getItems()) {
			if (selectItem.getValue().equals(option)) {
				return selectItem;
			}
		}

		return null;

	}

	/**
	 * Method remove old permission according to name of permission.
	 * 
	 * @param name
	 *            Permission name.
	 */
	@Transactional
	public void clearOldPermissions(String name) {

		Permission permission = permissionRepo.findByName(name);

		if (permission != null) {
			List<Role> oldRoleList = wkfService.roleRepo.all()
					.filter("self.permissions.id = ?1", permission.getId())
					.fetch();
			for (Role role : oldRoleList) {
				role.removePermission(permission);
				wkfService.roleRepo.save(role);
			}
		}
	}

	/**
	 * Add read permission into roles selected in WkfNode.
	 * 
	 * @param name
	 *            Permission name.
	 * @param node
	 *            WkfNode containing roles.
	 */
//	@Transactional
//	public void addNodePermissions(String name, WkfNode node) {
//
//		Set<Role> roles = node.getRoleSet();
//
//		if (roles == null || roles.isEmpty()) {
//			return;
//		}
//
//		Permission permission = permissionRepo.findByName(name);
//		if (permission == null) {
//			permission = new Permission(name);
//			permission.setCanRead(true);
//			permission.setCondition("self." + WkfService.WKF_STATUS + " = '"
//					+ node.getName() + "'");
//			permission.setObject(node.getWkf().getMetaModel().getFullName());
//			permission = permissionRepo.save(permission);
//		}
//
//		for (Role role : roles) {
//			role.addPermission(permission);
//			wkfService.roleRepo.save(role);
//		}
//	}

	/**
	 * Create/Update MenuBuilder from given parameters.
	 * 
	 * @param title
	 *            Title of menu.
	 * @param name
	 *            Name of menu.
	 * @param domain
	 *            Domain condition to set in action related to menu.
	 * @param parent
	 *            Parent menu name.
	 * @param order
	 *            Menu order.
	 * @return MenuBuilder created or updated.
	 */
	@Transactional
	public MenuBuilder addMenuBuilder(String title, String name, String domain,
			MetaMenu parentMenu, Integer order, MenuBuilder parentBuilder, MetaModule metaModule) {
		MenuBuilder menuBuilder = menuBuilderRepo.findByName(name);
		if (menuBuilder == null) {
			menuBuilder = new MenuBuilder(name);
		}
		menuBuilder.setTitle(title);
		menuBuilder.setEdited(true);
		menuBuilder.setRecorded(false);
		menuBuilder.setOrder(order);
		menuBuilder.setMenuBuilder(parentBuilder);
		menuBuilder.setMetaMenu(parentMenu);
		menuBuilder.setMetaModule(metaModule);
		
		ActionBuilder builder = getActionBuilder(menuBuilder, metaModule, domain);
		menuBuilder.setActionBuilder(builder);
//		menuBuilder.setMetaModel(wkfService.workflow.getMetaModel());
//		menuBuilder.setDomain(domain);
		return menuBuilderRepo.save(menuBuilder);
		
	}
	
	private ActionBuilder getActionBuilder(MenuBuilder menuBuilder, MetaModule metaModule, String domain) {
		
		ActionBuilder builder = menuBuilder.getActionBuilder();
		
		String name = menuBuilder.getName().replace("-", ".");
		
		if (builder == null) {
			builder = actionBuilderRepo.all().filter("self.metaModule = ?1 and self.name = ?2", metaModule, name).fetchOne();
		}
		
		if (builder == null) {
			builder = new ActionBuilder(name);
			builder.setTypeSelect(2);
		}
		
		builder.setDomainCondition(domain);
		builder.clearViewBuilderSet();
		MetaModel model = wkfService.workflow.getMetaModel();
		String gridName = ViewLoaderService.getDefaultViewName(model.getName(), "grid");
		ViewBuilder grid = viewLoaderService.getViewBuilder(metaModule.getName(), gridName, "grid");
		if (grid != null) {
			builder.addViewBuilderSetItem(grid);
		}
		builder.addViewBuilderSetItem(wkfService.workflow.getViewBuilder());
		builder.setMetaModel(model);
		builder.setMetaModule(metaModule);
		builder.setTitle(menuBuilder.getTitle());
		
		return builder;
		
	}

}
