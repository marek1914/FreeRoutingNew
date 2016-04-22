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
 * Created on 23. Februar 2004, 08:18
 */
package autoroute;

import java.util.Set;
import library.LibPadstack;
import planar.PlaPoint;
import planar.PlaPointFloat;
import planar.PlaPointInt;
import planar.Polyline;
import autoroute.varie.ArtLocateResult;
import board.RoutingBoard;
import board.infos.BrdViaInfo;
import board.items.BrdAbitPin;
import board.items.BrdItem;
import board.items.BrdTrace;
import board.items.BrdTracePolyline;
import board.varie.ItemSelectionChoice;
import board.varie.ItemSelectionFilter;
import board.varie.TestLevel;

/**
 * Inserts the traces and vias of the connection found by the autoroute algorithm.
 *
 * @author Alfons Wirtz
 */
public final class ArtConnectionInsert
   {
   private static final String classname="ArtInsertConnection.";
   
   private final RoutingBoard r_board;
   private final ArtControl ctrl;
   
   private PlaPointInt last_corner = null;
   private PlaPointInt first_corner = null;

   public ArtConnectionInsert( RoutingBoard p_board, ArtControl p_ctrl )
      {
      r_board = p_board;
      ctrl = p_ctrl;
      }

   /**
    * Actually try to insert the given connection
    * @param p_connection
    * @return true if it does it or false if it fails
    */
   public boolean insert (ArtConnectionLocate p_connection)
      {
      if (p_connection == null )
         {
         r_board.userPrintln(classname+"insert: p_connection == null");
         return false;
         }
      
      if ( p_connection.connection_items == null)
         {
         r_board.userPrintln(classname+"insert: connection_items == null");
         return false;
         }

      int curr_layer = p_connection.target_layer;
      
      for ( ArtLocateResult curr_new_item : p_connection.connection_items )
         {
         
         if (! insert_via_done(curr_new_item.corners[0], curr_layer, curr_new_item.layer))
            {
            r_board.userPrintln(classname+"insert_via FAIL");
            return false;
            }
         
         curr_layer = curr_new_item.layer;
         
         if (! insert_trace_done(curr_new_item))
            {
            r_board.userPrintln(classname+"insert trace failed for net "+ctrl.net_no);
            return false;
            }
         }
      
      if (! insert_via_done(last_corner, curr_layer, p_connection.start_layer))
         {
         r_board.userPrintln(classname+"insert_via on last corner FAIL");
         return false;
         }
      
      if (p_connection.target_item instanceof BrdTracePolyline)
         {
         BrdTracePolyline to_trace = (BrdTracePolyline) p_connection.target_item;
         r_board.connect_to_trace(first_corner, to_trace, ctrl.trace_half_width[p_connection.start_layer], ctrl.trace_clearance_class_no);
         }
      
      if (p_connection.start_item instanceof BrdTracePolyline)
         {
         BrdTracePolyline to_trace = (BrdTracePolyline) p_connection.start_item;
         r_board.connect_to_trace(last_corner, to_trace, ctrl.trace_half_width[p_connection.target_layer], ctrl.trace_clearance_class_no);
         }
      
      r_board.normalize_traces(ctrl.net_no);
      
      return true;
      }


   /**
    * Inserts the trace by shoving aside obstacle traces and vias.
    * @return true if all is fine, false if that was not possible for the whole trace.
    */
   private boolean insert_trace_done(ArtLocateResult p_trace)
      {
      if (p_trace.corners.length == 1)
         {
         last_corner = p_trace.corners[0];
         return true;
         }
      
      boolean result = true;

      // switch off correcting connection to pin because it may get wrong in inserting the polygon line for line.
      double saved_edge_to_turn_dist = r_board.brd_rules.set_pin_edge_to_turn_dist(-1);

      // Look for pins at the start and the end of p_trace in case that neckdown is necessary
      BrdAbitPin start_pin = null;
      BrdAbitPin end_pin = null;
      
      if (ctrl.with_neckdown)
         {
         ItemSelectionFilter item_filter = new ItemSelectionFilter(ItemSelectionChoice.PINS);
         PlaPoint curr_end_corner = p_trace.corners[0];
         for (int index = 0; index < 2; ++index)
            {
            Set<BrdItem> picked_items = r_board.pick_items(curr_end_corner, p_trace.layer, item_filter);
            for (BrdItem curr_item : picked_items)
               {
               board.items.BrdAbitPin curr_pin = (board.items.BrdAbitPin) curr_item;
               if (curr_pin.contains_net(ctrl.net_no) && curr_pin.get_center().equals(curr_end_corner))
                  {
                  if (index == 0)
                     {
                     start_pin = curr_pin;
                     }
                  else
                     {
                     end_pin = curr_pin;
                     }
                  }
               }
            curr_end_corner = p_trace.corners[p_trace.corners.length - 1];
            }
         }
      
      
      int[] net_no_arr = new int[1];
      net_no_arr[0] = ctrl.net_no;

      int from_corner_no = 0;
      for (int i = 1; i < p_trace.corners.length; ++i)
         {
         PlaPoint[] curr_corner_arr = new PlaPoint[i - from_corner_no + 1];
         for (int j = from_corner_no; j <= i; ++j)
            {
            curr_corner_arr[j - from_corner_no] = p_trace.corners[j];
            }
         Polyline insert_polyline = new Polyline(curr_corner_arr);
         PlaPoint ok_point = r_board.insert_trace_polyline(insert_polyline, ctrl.trace_half_width[p_trace.layer], p_trace.layer, net_no_arr, ctrl.trace_clearance_class_no,
               ctrl.max_shove_trace_recursion_depth, ctrl.max_shove_via_recursion_depth, ctrl.max_spring_over_recursion_depth, Integer.MAX_VALUE, ctrl.pull_tight_accuracy, true, null);
         boolean neckdown_inserted = false;
         if (ok_point != null && ok_point != insert_polyline.last_corner() && ctrl.with_neckdown && curr_corner_arr.length == 2)
            {
            neckdown_inserted = insert_neckdown(ok_point, curr_corner_arr[1], p_trace.layer, start_pin, end_pin);
            }
         if (ok_point == insert_polyline.last_corner() || neckdown_inserted)
            {
            from_corner_no = i;
            }
         else if (ok_point == insert_polyline.first_corner() && i != p_trace.corners.length - 1)
            {
            // if ok_point == insert_polyline.first_corner() the spring over may have failed.
            // Spring over may correct the situation because an insertion, which is ok with clearance compensation
            // may cause violations without clearance compensation.
            // In this case repeating the insertion with more distant corners may allow the spring_over to correct the situation.
            if (from_corner_no > 0)
               {
               // p_trace.corners[i] may be inside the offset for the substitute trace around
               // a spring_over obstacle (if clearance compensation is off).
               if (curr_corner_arr.length < 3)
                  {
                  // first correction
                  --from_corner_no;
                  }
               }

            System.out.println("InsertFoundConnectionAlgo: violation corrected");
            }
         else
            {
            result = false;
            break;
            }
         }
      
      
      
      
      if (r_board.get_test_level().ordinal() < TestLevel.ALL_DEBUGGING_OUTPUT.ordinal())
         {
         for (int i = 0; i < p_trace.corners.length - 1; ++i)
            {
            BrdTrace trace_stub = r_board.get_trace_tail(p_trace.corners[i], p_trace.layer, net_no_arr);
            if (trace_stub != null)
               {
               r_board.remove_item(trace_stub);
               }
            }
         }
      
      r_board.brd_rules.set_pin_edge_to_turn_dist(saved_edge_to_turn_dist);
      
      if ( first_corner == null)
         {
         first_corner = p_trace.corners[0];
         }
      
      last_corner = p_trace.corners[p_trace.corners.length - 1];
      
      return result;
      }

   boolean insert_neckdown(PlaPoint p_from_corner, PlaPoint p_to_corner, int p_layer, BrdAbitPin p_start_pin, BrdAbitPin p_end_pin)
      {
      if (p_start_pin != null)
         {
         PlaPoint ok_point = try_neck_down(p_to_corner, p_from_corner, p_layer, p_start_pin, true);

         if (ok_point == p_from_corner)  return true;
         }
      
      if (p_end_pin != null)
         {
         PlaPoint ok_point = try_neck_down(p_from_corner, p_to_corner, p_layer, p_end_pin, false);

         if (ok_point == p_to_corner) return true;
         }
      return false;
      }

   private PlaPoint try_neck_down(PlaPoint p_from_corner, PlaPoint p_to_corner, int p_layer, BrdAbitPin p_pin, boolean p_at_start)
      {
      if (!p_pin.is_on_layer(p_layer)) return null;

      PlaPointFloat pin_center = p_pin.get_center().to_float();
      double curr_clearance = r_board.brd_rules.clearance_matrix.value_at(ctrl.trace_clearance_class_no, p_pin.clearance_class_no(), p_layer);
      double pin_neck_down_distance = 2 * (0.5 * p_pin.get_max_width(p_layer) + curr_clearance);

      if (pin_center.distance(p_to_corner.to_float()) >= pin_neck_down_distance) return null;

      int neck_down_halfwidth = p_pin.get_trace_neckdown_halfwidth(p_layer);

      if (neck_down_halfwidth >= ctrl.trace_half_width[p_layer]) return null;

      PlaPointFloat float_from_corner = p_from_corner.to_float();
      PlaPointFloat float_to_corner = p_to_corner.to_float();

      final int TOLERANCE = 2;

      int[] net_no_arr = new int[1];
      net_no_arr[0] = ctrl.net_no;

      double ok_length = r_board.check_trace_segment(p_from_corner, p_to_corner, p_layer, net_no_arr, ctrl.trace_half_width[p_layer], ctrl.trace_clearance_class_no, true);

      if (ok_length >= Integer.MAX_VALUE) return p_from_corner;
      
      ok_length -= TOLERANCE;
      PlaPoint neck_down_end_point;
      if (ok_length <= TOLERANCE)
         {
         neck_down_end_point = p_from_corner;
         }
      else
         {
         PlaPointFloat float_neck_down_end_point = float_from_corner.change_length(float_to_corner, ok_length);
         neck_down_end_point = float_neck_down_end_point.round();
         // add a corner in case neck_down_end_point is not exactly on the line from p_from_corner to p_to_corner
         boolean horizontal_first = Math.abs(float_from_corner.point_x - float_neck_down_end_point.point_x) >= Math.abs(float_from_corner.point_y - float_neck_down_end_point.point_y);
         PlaPointInt add_corner = ArtConnectionLocate.calculate_additional_corner(float_from_corner, float_neck_down_end_point, horizontal_first, r_board.brd_rules.get_trace_snap_angle()).round();
         PlaPoint curr_ok_point = r_board.insert_trace_segment(p_from_corner, add_corner, ctrl.trace_half_width[p_layer], p_layer, net_no_arr, ctrl.trace_clearance_class_no,
               ctrl.max_shove_trace_recursion_depth, ctrl.max_shove_via_recursion_depth, ctrl.max_spring_over_recursion_depth, Integer.MAX_VALUE, ctrl.pull_tight_accuracy, true, null);

         if (curr_ok_point != add_corner) return p_from_corner;

         curr_ok_point = r_board.insert_trace_segment(add_corner, neck_down_end_point, ctrl.trace_half_width[p_layer], p_layer, net_no_arr, ctrl.trace_clearance_class_no,
               ctrl.max_shove_trace_recursion_depth, ctrl.max_shove_via_recursion_depth, ctrl.max_spring_over_recursion_depth, Integer.MAX_VALUE, ctrl.pull_tight_accuracy, true, null);

         if (curr_ok_point != neck_down_end_point) return p_from_corner;

         add_corner = ArtConnectionLocate.calculate_additional_corner(float_neck_down_end_point, float_to_corner, !horizontal_first, r_board.brd_rules.get_trace_snap_angle()).round();
         if (!add_corner.equals(p_to_corner))
            {
            curr_ok_point = r_board.insert_trace_segment(neck_down_end_point, add_corner, ctrl.trace_half_width[p_layer], p_layer, net_no_arr, ctrl.trace_clearance_class_no,
                  ctrl.max_shove_trace_recursion_depth, ctrl.max_shove_via_recursion_depth, ctrl.max_spring_over_recursion_depth, Integer.MAX_VALUE, ctrl.pull_tight_accuracy, true, null);

            if (curr_ok_point != add_corner) return p_from_corner;

            neck_down_end_point = add_corner;
            }
         }

      PlaPoint ok_point = r_board.insert_trace_segment(neck_down_end_point, p_to_corner, neck_down_halfwidth, p_layer, net_no_arr, ctrl.trace_clearance_class_no,
            ctrl.max_shove_trace_recursion_depth, ctrl.max_shove_via_recursion_depth, ctrl.max_spring_over_recursion_depth, Integer.MAX_VALUE, ctrl.pull_tight_accuracy, true, null);
      return ok_point;
      }

   /**
    * Search the cheapest via masks containing p_from_layer and p_to_layer, so that a forced via is possible at p_location with
    * this mask and inserts the via. 
    * @return false, if no suitable via mask was found or if the algorithm failed.
    */
   private boolean insert_via_done(PlaPoint p_location, int p_from_layer, int p_to_layer)
      {
      // no via necessary
      if (p_from_layer == p_to_layer) return true; 
      
      int from_layer;
      int to_layer;
      
      if (p_from_layer < p_to_layer)
         {
         // sort the input layers
         from_layer = p_from_layer;
         to_layer = p_to_layer;
         }
      else
         {
         from_layer = p_to_layer;
         to_layer = p_from_layer;
         }
      
      int[] net_no_arr = new int[1];
      net_no_arr[0] = ctrl.net_no;
      BrdViaInfo via_info = null;
      for (int index = 0; index < ctrl.via_rule.via_count(); ++index)
         {
         BrdViaInfo curr_via_info = ctrl.via_rule.get_via(index);
         LibPadstack curr_via_padstack = curr_via_info.get_padstack();

         if (curr_via_padstack.from_layer() > from_layer || curr_via_padstack.to_layer() < to_layer) continue;

         if (r_board.shove_via_algo.check(curr_via_info, p_location, net_no_arr, ctrl.max_shove_trace_recursion_depth, ctrl.max_shove_via_recursion_depth ))
            {
            via_info = curr_via_info;
            break;
            }
         }

      if (via_info == null)
         {
         System.out.print("InsertFoundConnectionAlgo: via mask not found for net ");
         System.out.println(ctrl.net_no);
         return false;
         }
      
      // insert the via
      if (! r_board.shove_via_algo.insert(via_info, p_location, net_no_arr, ctrl.trace_clearance_class_no, ctrl.trace_half_width, ctrl.max_shove_trace_recursion_depth,
            ctrl.max_shove_via_recursion_depth ))
         {
         System.out.print("InsertFoundConnectionAlgo: forced via failed for net ");
         System.out.println(ctrl.net_no);
         return false;
         }
      
      return true;
      }
   }