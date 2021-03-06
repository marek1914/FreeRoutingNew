/*
 *  Copyright (C) 2014  Alfons Wirtz  
 *   website www.freerouting.net
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License at <http://www.gnu.org/licenses/> 
 *   for more details.
 *
 * ComponentsWindow.java
 *
 * Created on 8. Maerz 2005, 05:56
 */

package gui.win;

import gui.BoardFrame;
import board.BrdComponents;
import board.infos.BrdComponent;

/**
 * Window displaying the components on the board.
 *
 * @author Alfons Wirtz
 */
public class WindowComponents extends WindowObjectListWithFilter
   {
   private static final long serialVersionUID = 1L;

   public WindowComponents(BoardFrame p_board_frame)
      {
      super(p_board_frame);
      java.util.ResourceBundle resources = java.util.ResourceBundle.getBundle("gui.resources.Default", p_board_frame.get_locale());
      setTitle(resources.getString("components"));
      p_board_frame.set_context_sensitive_help(this, "WindowObjectList_BoardComponents");
      }

   /**
    * Fills the list with the board components.
    */
   @Override
   protected void fill_list()
      {
      BrdComponents components = board_frame.board_panel.itera_board.get_routing_board().brd_components;
      BrdComponent[] sorted_arr = new BrdComponent[components.count()];
      for (int i = 0; i < sorted_arr.length; ++i)
         {
         sorted_arr[i] = components.get(i + 1);
         }
      java.util.Arrays.sort(sorted_arr);
      for (int i = 0; i < sorted_arr.length; ++i)
         {
         add_to_list(sorted_arr[i]);
         }
      gui_list.setVisibleRowCount(Math.min(components.count(), DEFAULT_TABLE_SIZE));
      }

   protected void select_instances()
      {
      @SuppressWarnings("deprecation")
      Object[] selected_components = gui_list.getSelectedValues();
      if (selected_components.length <= 0)
         {
         return;
         }
      board.RoutingBoard routing_board = board_frame.board_panel.itera_board.get_routing_board();
      java.util.Set<board.items.BrdItem> selected_items = new java.util.TreeSet<board.items.BrdItem>();
      java.util.Collection<board.items.BrdItem> board_items = routing_board.get_items();
      for (board.items.BrdItem curr_item : board_items)
         {
         if (curr_item.get_component_no() > 0)
            {
            board.infos.BrdComponent curr_component = routing_board.brd_components.get(curr_item.get_component_no());
            boolean component_matches = false;
            for (int i = 0; i < selected_components.length; ++i)
               {
               if (curr_component == selected_components[i])
                  {
                  component_matches = true;
                  break;
                  }
               }
            if (component_matches)
               {
               selected_items.add(curr_item);
               }
            }
         }
      board_frame.board_panel.itera_board.select_items(selected_items);
      board_frame.board_panel.itera_board.zoom_selection();
      }
   }
